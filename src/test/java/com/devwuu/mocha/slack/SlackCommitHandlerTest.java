package com.devwuu.mocha.slack;

import com.devwuu.mocha.domain.Aliases;
import com.devwuu.mocha.domain.Bean;
import com.devwuu.mocha.domain.Brew;
import com.devwuu.mocha.domain.Entry;
import com.devwuu.mocha.domain.MatchInfo;
import com.devwuu.mocha.domain.Note;
import com.devwuu.mocha.domain.NoteMeta;
import com.devwuu.mocha.domain.PendingNote;
import com.devwuu.mocha.domain.PhotoBuffer;
import com.devwuu.mocha.domain.Rating;
import com.devwuu.mocha.domain.Sourced;
import com.devwuu.mocha.domain.Tasting;
import com.devwuu.mocha.json.MochaObjectMapper;
import com.devwuu.mocha.llm.AliasGenerator;
import com.devwuu.mocha.llm.PhotoInfoExtractor;
import com.devwuu.mocha.render.NoteRenderer;
import com.devwuu.mocha.repository.JsonFileNoteRepository;
import com.devwuu.mocha.repository.NoteRepository;
import com.devwuu.mocha.repository.PendingStore;
import com.devwuu.mocha.repository.PhotoBufferStore;
import com.devwuu.mocha.repository.PhotoStore;
import com.devwuu.mocha.repository.StagedImage;
import com.devwuu.mocha.slack.inbound.IncomingAction;
import com.devwuu.mocha.slack.inbound.SlackPhotoIntake;
import com.devwuu.mocha.slack.outbound.MochaMessages;
import com.devwuu.mocha.slack.outbound.SlackResponder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TΔ8a(changes/0018): 커밋 경로 이관 후 동작 불변 단언(AC-Δ3 선행 확인) — [저장]/[취소] 버튼의
 * 커밋·렌더·배달·버튼 소진 체인을 flow 3종 미경유의 새 홈 {@link SlackCommitHandler}에 직접 대고 검증한다.
 * 단언 내용은 구 SlackConversationFlowsTest의 커밋 절(T3-5·TΔ2·TΔ3)에서 포팅했다 — 이관은 로직 변경이
 * 없어야 하므로(findings-TΔ0 §2) 기대값도 동일하다. 저장소는 실제 파일 I/O(@TempDir, 모듈 CLAUDE.md §5.2),
 * LLM(별칭 1콜)·Slack 전송은 fake.
 */
class SlackCommitHandlerTest {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    @TempDir
    Path dataDir;

    private final Clock clock = Clock.fixed(Instant.parse("2026-07-11T02:00:00Z"), SEOUL);

    private final FakePendingStore pendingStore = new FakePendingStore();
    private final FakeNoteRenderer noteRenderer = new FakeNoteRenderer();
    private final FakeSlackResponder responder = new FakeSlackResponder();
    private final FakePhotoStore photoStore = new FakePhotoStore();
    private final FakePhotoBufferStore photoBufferStore = new FakePhotoBufferStore();
    private final FakeAliasGenerator aliasGenerator = new FakeAliasGenerator();

    private NoteRepository noteRepository() {
        return new JsonFileNoteRepository(dataDir, MochaObjectMapper.create());
    }

    private SlackCommitHandler handler(NoteRepository repo) {
        // 커밋 경로는 다운로드·OCR을 부르지 않는다 — photoIntake는 스테이징 커밋·폐기·버퍼 정리 배선만 쓴다.
        SlackPhotoIntake photoIntake = new SlackPhotoIntake(pendingStore, responder,
                url -> new byte[0], photoStore, photoBufferStore, new PhotoInfoExtractor(null, 4),
                Duration.ofMinutes(10), clock);
        return new SlackCommitHandler(pendingStore, repo, noteRenderer, responder, aliasGenerator, photoIntake);
    }

    // --- record 커밋([저장]/[취소]) — 구 T3-5 절 포팅 ---

    @Test
    @DisplayName("AC-Δ3/AC-Δ1: [저장] 커밋 → pending clear → 방금 엔트리 카드 증분 렌더 → 카드 JPG 배달")
    void confirmSaveCommitsClearsAndDeliversCard() {
        NoteRepository repo = noteRepository();
        pendingStore.setPending(pendingWith("coffeevera-yirgacheffe"));

        handler(repo).confirmSave(action(AgentConversationRouter.ACTION_SAVE));

        Optional<Note> saved = repo.findBySlug("coffeevera-yirgacheffe");
        assertTrue(saved.isPresent(), "저장된 노트 JSON이 있어야 한다");
        assertEquals(1, saved.get().entries().size());
        assertTrue(dataDir.resolve("notes/coffeevera-yirgacheffe.json").toFile().isFile());

        assertEquals(1, pendingStore.clearCount, "커밋 후 pending을 폐기한다");
        assertEquals(List.of("coffeevera-yirgacheffe/2026-07-11"), noteRenderer.entryCards,
                "커밋 후 방금 엔트리 카드만 증분 렌더한다");
        assertEquals(0, noteRenderer.renderAllCount, "저장 시점은 전체 리렌더를 트리거하지 않는다");
        assertEquals(1, responder.images.size(), "방금 엔트리 카드 JPG를 채널에 올린다");
        assertEquals(Path.of("cards", "coffeevera-yirgacheffe", "2026-07-11-taste-1.jpg"), responder.images.get(0));
        assertEquals(List.of(MochaMessages.SAVE_DONE_CAPTION), responder.captions);
        assertTrue(responder.messages.isEmpty(), "정상 배달이면 폴백 텍스트가 없다");
    }

    @Test
    @DisplayName("AC-Δ3: 신규 노트 첫 [저장] 커밋 시 생성된 별칭이 노트에 저장된다(별칭 1콜 — LLM 개입은 이것뿐)")
    void confirmSavePersistsGeneratedAliasesForNewNote() {
        NoteRepository repo = noteRepository();
        pendingStore.setPending(pendingWith("coffeevera-yirgacheffe")); // match=NEW
        aliasGenerator.canned = new Aliases(List.of("커피베라 예가체프"), List.of("커피베라"));

        handler(repo).confirmSave(action(AgentConversationRouter.ACTION_SAVE));

        Note saved = repo.findBySlug("coffeevera-yirgacheffe").orElseThrow();
        assertEquals(List.of("커피베라 예가체프"), saved.aliases().coffeeName(), "생성된 커피명 별칭이 노트에 저장된다");
        assertEquals(List.of("커피베라"), saved.aliases().roastery(), "생성된 로스터리 별칭이 노트에 저장된다");
        assertEquals(1, aliasGenerator.calls, "커밋 경로의 LLM 호출은 별칭 생성 1콜뿐(AC-Δ3)");
    }

    @Test
    @DisplayName("plan §7: 별칭 생성 콜 실패 시에도 노트 저장은 성공하고 aliases만 빈 배열이다")
    void confirmSaveKeepsCommitWhenAliasGenerationFails() {
        NoteRepository repo = noteRepository();
        pendingStore.setPending(pendingWith("coffeevera-yirgacheffe")); // match=NEW
        aliasGenerator.failed = true; // 경계 계약: 콜 실패는 예외가 아니라 빈 별칭 수렴(plan §7, V-13)

        handler(repo).confirmSave(action(AgentConversationRouter.ACTION_SAVE));

        Note saved = repo.findBySlug("coffeevera-yirgacheffe").orElseThrow();
        assertEquals(1, saved.entries().size(), "엔트리는 정상 저장된다");
        assertTrue(saved.aliases().coffeeName().isEmpty(), "실패 시 커피명 별칭은 빈 배열");
        assertTrue(saved.aliases().roastery().isEmpty(), "실패 시 로스터리 별칭은 빈 배열");
        assertEquals(1, pendingStore.clearCount, "커밋은 완료됐다");
    }

    @Test
    @DisplayName("ADR-37: EXISTING 재기록 [저장] 커밋 시 이번 기록의 다른 표기가 노트 별칭에 무콜 축적된다")
    void confirmSaveAccumulatesObservedAliasesForExistingNote() {
        NoteRepository repo = noteRepository();
        seedNote(repo, "ethiopia-chelbesa", "Ethiopia Chelbesa", "FroB", LocalDate.of(2026, 7, 1));
        Entry entry = entry(LocalDate.of(2026, 7, 11), "새콤", OffsetDateTime.now(clock));
        Note draft = new Note(
                "ethiopia-chelbesa", Sourced.user("에티오피아 첼베사"), Sourced.user("프롭"),
                List.of(), null, null, List.of(),
                List.of(entry), OffsetDateTime.now(clock), OffsetDateTime.now(clock));
        pendingStore.setPending(new PendingNote(
                draft, MatchInfo.existing("ethiopia-chelbesa", LocalDate.of(2026, 7, 11)),
                "1720000000.000999", OffsetDateTime.now(clock)));

        handler(repo).confirmSave(action(AgentConversationRouter.ACTION_SAVE));

        Note saved = repo.findBySlug("ethiopia-chelbesa").orElseThrow();
        assertEquals(List.of("에티오피아 첼베사"), saved.aliases().coffeeName(),
                "EXISTING 커밋은 이번 기록의 다른 커피명 표기를 별칭에 축적한다");
        assertEquals(List.of("프롭"), saved.aliases().roastery(), "로스터리 다른 표기도 별칭에 축적된다");
        assertEquals(0, aliasGenerator.calls, "EXISTING 커밋은 별칭 생성 콜이 없다(NEW 전용)");
    }

    @Test
    @DisplayName("AC-Δ6/FR-16: 회차 2개 [저장] → 그 엔트리의 회차 카드 4장 전부를 순서대로 배달한다 — 캡션은 첫 카드에만")
    void confirmSaveDeliversAllBrewCards() {
        NoteRepository repo = noteRepository();
        pendingStore.setPending(pendingWith("coffeevera-yirgacheffe"));
        noteRenderer.cardSuffixes = List.of("taste-1", "recipe-1", "taste-2", "recipe-2");

        handler(repo).confirmSave(action(AgentConversationRouter.ACTION_SAVE));

        assertEquals(List.of(
                        Path.of("cards", "coffeevera-yirgacheffe", "2026-07-11-taste-1.jpg"),
                        Path.of("cards", "coffeevera-yirgacheffe", "2026-07-11-recipe-1.jpg"),
                        Path.of("cards", "coffeevera-yirgacheffe", "2026-07-11-taste-2.jpg"),
                        Path.of("cards", "coffeevera-yirgacheffe", "2026-07-11-recipe-2.jpg")),
                responder.images, "회차 카드 전부가 렌더 순서 그대로 배달된다");
        assertEquals(MochaMessages.SAVE_DONE_CAPTION, responder.captions.get(0), "캡션은 첫 카드에만 싣는다");
        assertEquals(1, responder.captions.stream().filter(c -> c != null).count(),
                "같은 안내를 카드 수만큼 반복하지 않는다");
        assertTrue(responder.messages.isEmpty(), "전량 배달이면 폴백·부분 안내 텍스트가 없다");
    }

    @Test
    @DisplayName("plan §7/FR-16: 카드 일부 전송 실패 → 저장 유지, 성공분은 배달하고 실패분만 안내한다(부분 폴백)")
    void confirmSaveDeliversSuccessfulCardsOnPartialFailure() {
        NoteRepository repo = noteRepository();
        pendingStore.setPending(pendingWith("coffeevera-yirgacheffe"));
        noteRenderer.cardSuffixes = List.of("taste-1", "recipe-1", "taste-2", "recipe-2");
        responder.failPaths.add(Path.of("cards", "coffeevera-yirgacheffe", "2026-07-11-recipe-1.jpg"));

        handler(repo).confirmSave(action(AgentConversationRouter.ACTION_SAVE));

        assertTrue(repo.findBySlug("coffeevera-yirgacheffe").isPresent(), "일부 실패해도 저장은 유지된다");
        assertEquals(List.of(
                        Path.of("cards", "coffeevera-yirgacheffe", "2026-07-11-taste-1.jpg"),
                        Path.of("cards", "coffeevera-yirgacheffe", "2026-07-11-taste-2.jpg"),
                        Path.of("cards", "coffeevera-yirgacheffe", "2026-07-11-recipe-2.jpg")),
                responder.images, "성공분은 배달된다");
        assertEquals(List.of(MochaMessages.SAVE_DONE_PARTIAL_IMAGE), responder.messages,
                "전량 폴백이 아니라 실패분만 안내한다");
        assertEquals(List.of(MochaMessages.FINALIZE_SAVED), responder.finalizeStatuses, "버튼 소진은 그대로 진행된다");
    }

    @Test
    @DisplayName("AC-18: 카드 렌더 실패 → 노트 JSON 저장은 유지되고 안내 텍스트로 폴백한다")
    void confirmSaveKeepsCommitWhenRenderFails() {
        NoteRepository repo = noteRepository();
        pendingStore.setPending(pendingWith("coffeevera-yirgacheffe"));
        noteRenderer.renderFailure = new IllegalStateException("Chromium 미기동");

        handler(repo).confirmSave(action(AgentConversationRouter.ACTION_SAVE));

        assertTrue(repo.findBySlug("coffeevera-yirgacheffe").isPresent(), "렌더 실패해도 저장은 유지된다(AC-18)");
        assertEquals(1, pendingStore.clearCount, "커밋은 완료됐다");
        assertTrue(responder.images.isEmpty(), "카드는 배달되지 못했다");
        assertEquals(List.of(MochaMessages.SAVE_DONE_NO_IMAGE), responder.messages, "안내 텍스트로 폴백한다");
    }

    @Test
    @DisplayName("AC-18: 카드 전송(files.upload) 실패 → 저장은 유지되고 안내 텍스트로 폴백한다")
    void confirmSaveKeepsCommitWhenUploadFails() {
        NoteRepository repo = noteRepository();
        pendingStore.setPending(pendingWith("coffeevera-yirgacheffe"));
        responder.imageFailure = new IllegalStateException("files.upload 실패");

        handler(repo).confirmSave(action(AgentConversationRouter.ACTION_SAVE));

        assertTrue(repo.findBySlug("coffeevera-yirgacheffe").isPresent(), "업로드 실패해도 저장은 유지된다(AC-18)");
        assertEquals(1, noteRenderer.entryCards.size(), "카드는 렌더됐다(전송에서 실패)");
        assertEquals(List.of(MochaMessages.SAVE_DONE_NO_IMAGE), responder.messages, "안내 텍스트로 폴백한다");
    }

    @Test
    @DisplayName("V-7: 만료/부재 pending에 [저장] → 커밋하지 않고 만료 안내 + 스테이징 정리")
    void confirmSaveRejectsWhenNoPending() {
        NoteRepository repo = noteRepository();
        pendingStore.setPending(null); // get()이 빈 Optional(만료분은 store가 만료 처리 — V-7)

        handler(repo).confirmSave(action(AgentConversationRouter.ACTION_SAVE));

        assertTrue(repo.findAll().isEmpty(), "만료/부재 시 어떤 노트도 저장되지 않는다");
        assertTrue(noteRenderer.entryCards.isEmpty(), "커밋이 없으면 카드 렌더·배달도 없다");
        assertTrue(responder.images.isEmpty());
        assertEquals(1, photoStore.discardCount, "대기 중이던 스테이징 사진도 정리한다(FR-10)");
        assertEquals(List.of(MochaMessages.NOTHING_TO_SAVE), responder.messages);
    }

    @Test
    @DisplayName("AC-4: [취소] → pending 폐기, 어떤 노트 JSON도 생성·변경되지 않는다")
    void cancelDiscardsPendingWithoutSaving() {
        NoteRepository repo = noteRepository();
        pendingStore.setPending(pendingWith("coffeevera-yirgacheffe"));

        handler(repo).cancel(action(AgentConversationRouter.ACTION_CANCEL));

        assertEquals(1, pendingStore.clearCount, "취소는 pending을 폐기한다");
        assertTrue(repo.findAll().isEmpty(), "취소 시 저장은 일어나지 않는다(AC-4)");
        assertTrue(noteRenderer.entryCards.isEmpty());
        assertEquals(List.of(MochaMessages.CANCELED), responder.messages);
    }

    @Test
    @DisplayName("손상된 pending(slug 결손)에 [저장] → 저장하지 않고 방어 안내한다")
    void confirmSaveRejectsBrokenPending() {
        NoteRepository repo = noteRepository();
        pendingStore.setPending(pendingWith(null)); // slug 미할당 draft

        handler(repo).confirmSave(action(AgentConversationRouter.ACTION_SAVE));

        assertTrue(repo.findAll().isEmpty(), "slug 없는 draft는 저장하지 않는다");
        assertTrue(noteRenderer.entryCards.isEmpty());
        assertEquals(0, pendingStore.clearCount, "손상 pending은 커밋 clear 대상이 아니다");
        assertEquals(List.of(MochaMessages.BROKEN_PENDING), responder.messages);
    }

    // --- 버튼 1회 소진(ADR-20, AC-22) — 구 TΔ2(changes/0009) 절 포팅 ---

    @Test
    @DisplayName("AC-22: [저장] 완료 시 버튼 소진(finalizePreview) 호출 — '저장 완료' 상태 문구로 교체된다")
    void confirmSaveFinalizesPreviewButtons() {
        NoteRepository repo = noteRepository();
        PendingNote pending = pendingWith("coffeevera-yirgacheffe");
        pendingStore.setPending(pending);

        handler(repo).confirmSave(action(AgentConversationRouter.ACTION_SAVE));

        assertEquals(List.of(MochaMessages.FINALIZE_SAVED), responder.finalizeStatuses);
        assertEquals(1, responder.finalizePendings.size());
        assertEquals(pending.previewTs(), responder.finalizePendings.get(0).previewTs(),
                "버튼 소진 대상 미리보기 메시지(previewTs)가 넘어가야 한다");
    }

    @Test
    @DisplayName("AC-22: [취소] 완료 시 버튼 소진(finalizePreview) 호출 — '취소됨' 상태 문구로 교체된다")
    void cancelFinalizesPreviewButtons() {
        NoteRepository repo = noteRepository();
        pendingStore.setPending(pendingWith("coffeevera-yirgacheffe"));

        handler(repo).cancel(action(AgentConversationRouter.ACTION_CANCEL));

        assertEquals(List.of(MochaMessages.FINALIZE_CANCELED), responder.finalizeStatuses);
    }

    @Test
    @DisplayName("V-7: 만료/부재 pending에 [취소] → 버튼 소진 대상이 없어 finalizePreview를 호출하지 않는다")
    void cancelSkipsFinalizeWhenNoPending() {
        NoteRepository repo = noteRepository();
        pendingStore.setPending(null);

        handler(repo).cancel(action(AgentConversationRouter.ACTION_CANCEL));

        assertTrue(responder.finalizeStatuses.isEmpty(), "갱신할 미리보기가 없으면 버튼 소진을 호출하지 않는다");
        assertEquals(List.of(MochaMessages.CANCELED), responder.messages);
    }

    @Test
    @DisplayName("ADR-20: 버튼 소진(chat.update) 실패를 주입해도 노트 커밋·카드 배달은 정상 완료된다(로그만)")
    void confirmSaveKeepsCommitAndDeliveryWhenFinalizeFails() {
        NoteRepository repo = noteRepository();
        pendingStore.setPending(pendingWith("coffeevera-yirgacheffe"));
        responder.finalizeFailure = new IllegalStateException("chat.update 실패");

        handler(repo).confirmSave(action(AgentConversationRouter.ACTION_SAVE));

        assertTrue(repo.findBySlug("coffeevera-yirgacheffe").isPresent(), "버튼 소진 실패해도 저장은 유지된다");
        assertEquals(1, pendingStore.clearCount, "커밋은 완료됐다");
        assertEquals(1, responder.images.size(), "카드 배달도 정상 완료된다");
        assertTrue(responder.messages.isEmpty(), "정상 배달이면 폴백 텍스트가 없다");
    }

    @Test
    @DisplayName("AC-Δ3(회귀 가드): [저장] 커밋 → pending clear → 카드 배달 → 버튼 소진 순서가 종전과 동일하다")
    void confirmSavePreservesCommitFlowOrder() {
        List<String> order = new ArrayList<>();
        NoteRepository repo = new RecordingNoteRepository(noteRepository(), order);
        pendingStore.order = order;
        responder.order = order;
        pendingStore.setPending(pendingWith("coffeevera-yirgacheffe"));

        handler(repo).confirmSave(action(AgentConversationRouter.ACTION_SAVE));

        assertEquals(List.of("commit", "clear", "deliver", "finalize"), order,
                "저장 커밋 → pending clear → 카드 배달 → 버튼 소진 순서가 유지되어야 한다");
    }

    // --- 사진 커밋(FR-10, changes/0014 ADR-32) ---

    @Test
    @DisplayName("ADR-32: [저장] 시 스테이징 사진을 photos/<slug>/<date>/로 아카이브 커밋하고 노트 JSON엔 싣지 않는다")
    void confirmSaveArchivesStagedPhotos() {
        NoteRepository repo = noteRepository();
        photoStore.staged.add("a.jpg"); // 대기 중 스테이징 사진
        pendingStore.setPending(pendingWith("coffeevera-yirgacheffe")); // 엔트리 date=2026-07-11

        handler(repo).confirmSave(action(AgentConversationRouter.ACTION_SAVE));

        assertEquals(List.of("photos/coffeevera-yirgacheffe/2026-07-11/a.jpg"), photoStore.committed,
                "스테이징 사진이 확정 폴더 경로로 아카이브 커밋된다");
        assertTrue(photoStore.staged.isEmpty(), "commit 후 스테이징은 비워진다");
        assertEquals(1, repo.findBySlug("coffeevera-yirgacheffe").orElseThrow().entries().size(), "엔트리는 저장된다");
    }

    @Test
    @DisplayName("AC-4/FR-10: [취소] 시 대기 중이던 스테이징 사진·버퍼도 함께 폐기된다")
    void cancelDiscardsStagedPhotos() {
        NoteRepository repo = noteRepository();
        photoStore.staged.add("a.jpg");
        pendingStore.setPending(pendingWith("coffeevera-yirgacheffe"));

        handler(repo).cancel(action(AgentConversationRouter.ACTION_CANCEL));

        assertEquals(1, photoStore.discardCount, "취소는 스테이징 사진을 폐기한다");
        assertTrue(photoStore.staged.isEmpty());
        assertEquals(1, photoBufferStore.clearCount, "사진 버퍼도 함께 정리한다");
    }

    // --- edit 커밋(FR-21, changes/0012) — 구 TΔ3 절 포팅 ---

    @Test
    @DisplayName("AC-Δ3: edit [저장] 날짜 이동 → applyEdit 커밋 후 옛 date 카드 삭제 → 새 date 카드 증분 렌더·배달")
    void confirmSaveEditMovesDateAndCleansOldCard() {
        NoteRepository repo = noteRepository();
        seedEditableNote(repo, "yirga", LocalDate.of(2026, 7, 8));
        pendingStore.setPending(editPending("yirga", LocalDate.of(2026, 7, 8), LocalDate.of(2026, 7, 9), "고친 감상"));

        handler(repo).confirmSave(action(AgentConversationRouter.ACTION_SAVE));

        Note saved = repo.findBySlug("yirga").orElseThrow();
        assertEquals(1, saved.entries().size(), "이동이지 복제가 아니다 — 엔트리 총수 불변");
        assertEquals(LocalDate.of(2026, 7, 9), saved.entries().get(0).date(), "엔트리가 새 date로 이동");
        assertEquals("고친 감상", tasteOf(saved.entries().get(0)), "수정 내용 반영");
        assertEquals(1, pendingStore.clearCount, "커밋 후 pending 폐기");
        assertEquals(List.of("yirga/2026-07-08→2026-07-09"), photoStore.moves, "사진 폴더도 새 date로 이동");
        assertEquals(List.of("yirga/2026-07-08"), noteRenderer.removedCards, "옛 date 카드 삭제");
        assertEquals(List.of("yirga/2026-07-09"), noteRenderer.entryCards, "새 date 카드만 증분 렌더");
        assertEquals(0, noteRenderer.renderAllCount, "edit 저장도 전체 리렌더를 트리거하지 않는다");
        assertEquals(List.of(Path.of("cards", "yirga", "2026-07-09-taste-1.jpg")), responder.images, "갱신 카드 배달");
        assertEquals(List.of(MochaMessages.FINALIZE_SAVED), responder.finalizeStatuses, "버튼 1회 소진");
        assertTrue(responder.messages.isEmpty(), "정상 배달이면 폴백 텍스트가 없다");
    }

    @Test
    @DisplayName("AC-Δ3: edit [저장] 날짜 유지 → 옛 카드 삭제 없이 해당 date 카드만 다시 굽는다")
    void confirmSaveEditWithoutDateMoveSkipsCardRemoval() {
        NoteRepository repo = noteRepository();
        seedEditableNote(repo, "yirga", LocalDate.of(2026, 7, 8));
        pendingStore.setPending(editPending("yirga", LocalDate.of(2026, 7, 8), LocalDate.of(2026, 7, 8), "고친 감상"));

        handler(repo).confirmSave(action(AgentConversationRouter.ACTION_SAVE));

        Note saved = repo.findBySlug("yirga").orElseThrow();
        assertEquals("고친 감상", tasteOf(saved.entries().get(0)), "필드 갱신 반영");
        assertTrue(noteRenderer.removedCards.isEmpty(), "날짜가 그대로면 카드 삭제가 없다(같은 경로 덮어쓰기)");
        assertTrue(photoStore.moves.isEmpty(), "날짜가 그대로면 사진 폴더 이동도 없다");
        assertEquals(List.of("yirga/2026-07-08"), noteRenderer.entryCards, "해당 date 카드 재렌더");
    }

    @Test
    @DisplayName("ADR-32/plan §7: 사진 폴더 이동 실패 → 커밋 유지, 새 카드 렌더·배달은 그대로 진행된다(best-effort)")
    void confirmSaveEditKeepsCommitWhenPhotoMoveFails() {
        NoteRepository repo = noteRepository();
        seedEditableNote(repo, "yirga", LocalDate.of(2026, 7, 8));
        pendingStore.setPending(editPending("yirga", LocalDate.of(2026, 7, 8), LocalDate.of(2026, 7, 9), "고친 감상"));
        photoStore.moveFailure = new IllegalStateException("사진 폴더 이동 실패");

        handler(repo).confirmSave(action(AgentConversationRouter.ACTION_SAVE));

        Note saved = repo.findBySlug("yirga").orElseThrow();
        assertEquals(LocalDate.of(2026, 7, 9), saved.entries().get(0).date(), "이동 실패해도 수정 커밋은 유지된다");
        assertEquals(List.of("yirga/2026-07-08"), noteRenderer.removedCards, "옛 카드 삭제는 그대로 진행");
        assertEquals(List.of("yirga/2026-07-09"), noteRenderer.entryCards, "새 카드 렌더도 그대로 진행");
        assertEquals(1, responder.images.size(), "카드 배달도 그대로 진행");
        assertTrue(responder.messages.isEmpty(), "이동 실패만으로 폴백 텍스트를 보내지 않는다(로그만)");
    }

    @Test
    @DisplayName("plan §7: 옛 카드 삭제 실패 → 커밋 유지, 새 카드 렌더·배달은 그대로 진행된다")
    void confirmSaveEditKeepsCommitWhenCardRemovalFails() {
        NoteRepository repo = noteRepository();
        seedEditableNote(repo, "yirga", LocalDate.of(2026, 7, 8));
        pendingStore.setPending(editPending("yirga", LocalDate.of(2026, 7, 8), LocalDate.of(2026, 7, 9), "고친 감상"));
        noteRenderer.removeFailure = new IllegalStateException("카드 삭제 실패");

        handler(repo).confirmSave(action(AgentConversationRouter.ACTION_SAVE));

        Note saved = repo.findBySlug("yirga").orElseThrow();
        assertEquals(LocalDate.of(2026, 7, 9), saved.entries().get(0).date(), "커밋은 유지된다");
        assertEquals(List.of("yirga/2026-07-09"), noteRenderer.entryCards, "새 카드 렌더는 그대로 진행");
        assertEquals(1, responder.images.size(), "카드 배달도 그대로 진행");
        assertTrue(responder.messages.isEmpty(), "삭제 실패만으로 폴백 텍스트를 보내지 않는다(로그만)");
    }

    @Test
    @DisplayName("plan §7: edit 커밋 후 카드 렌더 실패 → 커밋은 유지되고 안내 텍스트로 폴백한다")
    void confirmSaveEditKeepsCommitWhenRenderFails() {
        NoteRepository repo = noteRepository();
        seedEditableNote(repo, "yirga", LocalDate.of(2026, 7, 8));
        pendingStore.setPending(editPending("yirga", LocalDate.of(2026, 7, 8), LocalDate.of(2026, 7, 9), "고친 감상"));
        noteRenderer.renderFailure = new IllegalStateException("Chromium 미기동");

        handler(repo).confirmSave(action(AgentConversationRouter.ACTION_SAVE));

        Note saved = repo.findBySlug("yirga").orElseThrow();
        assertEquals(LocalDate.of(2026, 7, 9), saved.entries().get(0).date(), "렌더 실패해도 수정 커밋은 유지(AC-18 준용)");
        assertTrue(responder.images.isEmpty(), "카드는 배달되지 못했다");
        assertEquals(List.of(MochaMessages.SAVE_DONE_NO_IMAGE), responder.messages, "안내 텍스트로 폴백");
        assertEquals(List.of(MochaMessages.FINALIZE_SAVED), responder.finalizeStatuses, "버튼 소진은 그대로");
    }

    @Test
    @DisplayName("V-7 준용: [저장] 시 수정 대상 소실 → 커밋 없이 만료 안내 + pending·스테이징 정리")
    void confirmSaveEditRejectsWhenTargetGone() {
        NoteRepository repo = noteRepository(); // 대상 노트를 심지 않는다 — 소실 상황
        pendingStore.setPending(editPending("ghost", LocalDate.of(2026, 7, 8), LocalDate.of(2026, 7, 9), "고친 감상"));

        handler(repo).confirmSave(action(AgentConversationRouter.ACTION_SAVE));

        assertTrue(repo.findAll().isEmpty(), "대상 소실 시 어떤 노트도 저장·생성되지 않는다");
        assertTrue(noteRenderer.entryCards.isEmpty() && noteRenderer.removedCards.isEmpty(), "파생물 접촉 없음");
        assertEquals(1, pendingStore.clearCount, "죽은 edit pending은 폐기한다");
        assertEquals(1, photoStore.discardCount, "스테이징 사진도 만료 경로처럼 정리한다");
        assertEquals(List.of(MochaMessages.NOTHING_TO_SAVE), responder.messages, "만료 안내로 수렴(V-7 준용)");
    }

    @Test
    @DisplayName("ADR-32: edit [저장] 시 스테이징된 새 사진이 대상 엔트리 날짜의 아카이브 폴더로 커밋된다")
    void confirmSaveEditArchivesStagedPhotos() {
        NoteRepository repo = noteRepository();
        seedEditableNote(repo, "yirga", LocalDate.of(2026, 7, 8));
        photoStore.stage("U1", "b.jpg", new byte[]{1});
        pendingStore.setPending(editPending("yirga", LocalDate.of(2026, 7, 8), LocalDate.of(2026, 7, 9), "고친 감상"));

        handler(repo).confirmSave(action(AgentConversationRouter.ACTION_SAVE));

        assertEquals(List.of("photos/yirga/2026-07-09/b.jpg"), photoStore.committed,
                "스테이징 사진이 대상 엔트리 날짜 폴더로 커밋된다");
        assertTrue(photoStore.staged.isEmpty(), "커밋 후 스테이징은 비워진다");
        assertEquals(1, repo.findBySlug("yirga").orElseThrow().entries().size(), "엔트리는 저장되되 사진 필드는 없다");
    }

    @Test
    @DisplayName("손상 edit pending(target 결손)에 [저장] → 저장하지 않고 방어 안내한다")
    void confirmSaveRejectsEditPendingWithoutTarget() {
        NoteRepository repo = noteRepository();
        seedEditableNote(repo, "yirga", LocalDate.of(2026, 7, 8));
        PendingNote broken = new PendingNote(PendingNote.Mode.EDIT,
                editPending("yirga", LocalDate.of(2026, 7, 8), LocalDate.of(2026, 7, 9), "고친 감상").draft(),
                null, null, "1720000000.000999", OffsetDateTime.now()); // target 결손
        pendingStore.setPending(broken);

        handler(repo).confirmSave(action(AgentConversationRouter.ACTION_SAVE));

        assertEquals("원래 감상", tasteOf(repo.findBySlug("yirga").orElseThrow().entries().get(0)), "원본 무변화");
        assertEquals(0, pendingStore.clearCount, "손상 pending은 커밋 clear 대상이 아니다");
        assertEquals(List.of(MochaMessages.BROKEN_PENDING), responder.messages);
    }

    // ---- 헬퍼 ----

    // 회차 구조(changes/0021 ADR-59) 픽스처·접근 헬퍼 — 이 테스트의 엔트리는 회차 1개 전제.
    private static Entry entry(LocalDate date, String taste, OffsetDateTime ts) {
        return new Entry(date, List.of(new Brew(null, new Tasting(taste, null, Rating.GOOD))), ts);
    }

    private static String tasteOf(Entry entry) {
        return entry.brews().getFirst().tasting().myTaste();
    }

    private static PendingNote pendingWith(String slug) {
        Entry entry = entry(LocalDate.of(2026, 7, 11), "새콤하고 좋았다", OffsetDateTime.now());
        Note draft = new Note(
                slug, Sourced.user("커피베라 예가체프"),
                Sourced.user("커피베라"), List.of(new Bean(Sourced.search("에티오피아"), null)), null,
                null, List.of("https://example.com/coffee"),
                List.of(entry), OffsetDateTime.now(), OffsetDateTime.now());
        return new PendingNote(draft, MatchInfo.newNote(), "1720000000.000999", OffsetDateTime.now());
    }

    // mode=edit pending — 원본 (slug, targetDate) 엔트리를 newDate·새 감상으로 고치는 단일 엔트리 draft.
    private static PendingNote editPending(String slug, LocalDate targetDate, LocalDate newDate, String myTaste) {
        Entry entry = entry(newDate, myTaste, OffsetDateTime.now());
        Note draft = new Note(
                slug, Sourced.user("커피베라 예가체프"),
                Sourced.user("커피베라"), List.of(new Bean(Sourced.search("에티오피아"), null)), null,
                null, List.of(), List.of(entry), OffsetDateTime.now(), OffsetDateTime.now());
        return new PendingNote(PendingNote.Mode.EDIT, draft, new PendingNote.EditTarget(slug, targetDate),
                null, "1720000000.000999", OffsetDateTime.now());
    }

    // 수정 대상이 될 원본 노트(엔트리 1건)를 실제 파일로 심는다 — edit 커밋의 @TempDir 재료.
    private void seedEditableNote(NoteRepository repo, String slug, LocalDate date) {
        NoteMeta meta = new NoteMeta(
                Sourced.user("커피베라 예가체프"), Sourced.user("커피베라"),
                List.of(new Bean(Sourced.search("에티오피아"), null)),
                null, null, List.of());
        repo.upsertEntry(slug, meta, entry(date, "원래 감상", OffsetDateTime.now()));
    }

    private void seedNote(NoteRepository repo, String slug, String coffeeName, String roastery, LocalDate date) {
        NoteMeta meta = new NoteMeta(
                Sourced.user(coffeeName), Sourced.user(roastery), List.of(), null, null, List.of());
        repo.upsertEntry(slug, meta, entry(date, "좋았다", OffsetDateTime.now(clock)));
    }

    private static IncomingAction action(String actionId) {
        return new IncomingAction("U1", "C1", actionId, "slug", "1720000000.000999");
    }

    // ---- fakes (모듈 CLAUDE.md §5.2 — 외부 의존은 인터페이스 stub/fake, 구 SlackConversationFlowsTest에서 포팅) ----

    /** get()이 돌려줄 pending을 지정하고, put/clear 호출을 캡처하는 fake. */
    private static final class FakePendingStore implements PendingStore {
        private Optional<PendingNote> pending = Optional.empty();
        int clearCount = 0;
        List<String> order; // 커밋 흐름 순서 회귀 가드에서 여러 fake에 걸친 호출 순서를 캡처하는 공용 로그(비면 무시).

        void setPending(PendingNote p) {
            this.pending = Optional.ofNullable(p);
        }

        @Override
        public void put(String userId, PendingNote pending) {
            this.pending = Optional.of(pending);
        }

        @Override
        public Optional<PendingNote> get(String userId) {
            return pending;
        }

        @Override
        public void clear(String userId) {
            clearCount++;
            if (order != null) {
                order.add("clear");
            }
            pending = Optional.empty();
        }
    }

    /** renderAll 호출과 증분 renderEntryCard/removeEntryCard 호출(slug/date)을 캡처하고, 실패를 주입하는 fake. */
    private static final class FakeNoteRenderer implements NoteRenderer {
        int renderAllCount = 0;
        final List<String> entryCards = new ArrayList<>(); // "slug/date" 캡처
        final List<String> removedCards = new ArrayList<>();
        RuntimeException renderFailure;
        RuntimeException removeFailure;
        // 산출 카드 접미 — 기본은 감상 회차 1개, 다장 배달 테스트(TΔ5b)는 회차 2개 형태로 바꾼다.
        List<String> cardSuffixes = List.of("taste-1");

        @Override
        public void renderAll() {
            renderAllCount++;
        }

        @Override
        public List<Path> renderEntryCard(String slug, LocalDate date) {
            if (renderFailure != null) {
                throw renderFailure;
            }
            entryCards.add(slug + "/" + date);
            // 회차화(changes/0021 TΔ5a) 산출 형태 — CardFiles.expectedCards 순서(회차 오름차순, 감상 → 레시피).
            return cardSuffixes.stream()
                    .map(suffix -> Path.of("cards", slug, date + "-" + suffix + ".jpg"))
                    .toList();
        }

        @Override
        public void removeEntryCard(String slug, LocalDate date) {
            if (removeFailure != null) {
                throw removeFailure;
            }
            removedCards.add(slug + "/" + date);
        }
    }

    /** 전송된 안내 메시지·배달된 카드 이미지·버튼 소진 호출을 캡처하고, 업로드/소진 실패를 주입하는 fake. */
    private static final class FakeSlackResponder implements SlackResponder {
        final List<String> messages = new ArrayList<>();
        final List<Path> images = new ArrayList<>();
        final List<String> captions = new ArrayList<>();
        final List<String> finalizeStatuses = new ArrayList<>();
        final List<PendingNote> finalizePendings = new ArrayList<>();
        RuntimeException imageFailure;
        final List<Path> failPaths = new ArrayList<>(); // 특정 카드만 업로드 실패 주입(부분 폴백 검증, TΔ5b)
        RuntimeException finalizeFailure;
        List<String> order; // 커밋 흐름 순서 회귀 가드용 공용 로그(비면 무시).

        @Override
        public void post(String channelId, String text) {
            messages.add(text);
        }

        @Override
        public void postImage(String channelId, Path imagePath, String caption) {
            if (imageFailure != null) {
                throw imageFailure;
            }
            if (failPaths.contains(imagePath)) {
                throw new IllegalStateException("files.upload 실패: " + imagePath.getFileName());
            }
            images.add(imagePath);
            captions.add(caption);
            if (order != null) {
                order.add("deliver");
            }
        }

        @Override
        public void finalizePreview(String channelId, PendingNote pending, String statusText) {
            if (finalizeFailure != null) {
                throw finalizeFailure;
            }
            finalizePendings.add(pending);
            finalizeStatuses.add(statusText);
            if (order != null) {
                order.add("finalize");
            }
        }
    }

    /**
     * 별칭 생성 경계 fake — 커밋 경로의 유일한 LLM 접점(AC-Δ3), 호출 수를 센다.
     * 경계 계약대로 실패도 예외가 아니라 빈 별칭으로 수렴한다(plan §7 — 실패 수렴 자체는 어댑터
     * {@code OpenAiAliasGenerator} 테스트가 검증).
     */
    private static final class FakeAliasGenerator implements AliasGenerator {
        Aliases canned = Aliases.empty();
        boolean failed = false;
        int calls = 0;

        @Override
        public Aliases generate(String coffeeName, String roastery) {
            calls++;
            return failed ? Aliases.empty() : canned;
        }
    }

    /** 스테이징/커밋을 인메모리로 흉내내는 fake — 파일 규칙은 LocalPhotoStore 테스트가 따로 본다. */
    private static final class FakePhotoStore implements PhotoStore {
        final List<String> staged = new ArrayList<>();
        final List<byte[]> stagedBytes = new ArrayList<>();
        final List<String> committed = new ArrayList<>();
        final List<String> moves = new ArrayList<>(); // moveEntryPhotos "slug/from→to" 캡처
        RuntimeException moveFailure = null;
        int discardCount = 0;

        @Override
        public String stage(String userId, String filename, byte[] bytes) {
            staged.add(filename);
            stagedBytes.add(bytes);
            return filename;
        }

        @Override
        public List<StagedImage> readStaged(String userId) {
            List<StagedImage> images = new ArrayList<>();
            for (int i = 0; i < staged.size(); i++) {
                images.add(new StagedImage(staged.get(i), stagedBytes.get(i)));
            }
            return images;
        }

        @Override
        public List<String> commit(String userId, String slug, String date) {
            List<String> paths = staged.stream().map(n -> "photos/" + slug + "/" + date + "/" + n).toList();
            committed.addAll(paths);
            staged.clear();
            stagedBytes.clear();
            return paths;
        }

        @Override
        public void discard(String userId) {
            staged.clear();
            stagedBytes.clear();
            discardCount++;
        }

        @Override
        public void moveEntryPhotos(String slug, String fromDate, String toDate) {
            if (moveFailure != null) {
                throw moveFailure;
            }
            moves.add(slug + "/" + fromDate + "→" + toDate);
        }

        @Override
        public List<String> stagedUserIds() {
            return staged.isEmpty() ? List.of() : List.of("U1");
        }
    }

    /** 사진 버퍼 clear를 캡처하는 fake. */
    private static final class FakePhotoBufferStore implements PhotoBufferStore {
        private Optional<PhotoBuffer> buffer = Optional.empty();
        int clearCount = 0;

        @Override
        public void put(String userId, PhotoBuffer buffer) {
            this.buffer = Optional.of(buffer);
        }

        @Override
        public Optional<PhotoBuffer> get(String userId) {
            return buffer;
        }

        @Override
        public void clear(String userId) {
            clearCount++;
            buffer = Optional.empty();
        }
    }

    /**
     * 실제 파일 I/O({@link JsonFileNoteRepository})에 위임하되 커밋(upsertEntry/applyEdit) 시점을 공용 순서
     * 로그에 기록하는 래퍼 — "저장 커밋 → clear → 배달 → 버튼 소진" 순서를 여러 fake에 걸쳐 단언한다.
     */
    private static final class RecordingNoteRepository implements NoteRepository {
        private final NoteRepository delegate;
        private final List<String> order;

        RecordingNoteRepository(NoteRepository delegate, List<String> order) {
            this.delegate = delegate;
            this.order = order;
        }

        @Override
        public List<Note> findAll() {
            return delegate.findAll();
        }

        @Override
        public Optional<Note> findBySlug(String slug) {
            return delegate.findBySlug(slug);
        }

        @Override
        public String nextAvailableSlug(String base) {
            return delegate.nextAvailableSlug(base);
        }

        @Override
        public Note upsertEntry(String slug, NoteMeta meta, Entry entry, com.devwuu.mocha.domain.Aliases aliases) {
            Note saved = delegate.upsertEntry(slug, meta, entry, aliases);
            order.add("commit"); // 파일 쓰기가 실제로 끝난 뒤 기록 — clear보다 앞섬을 단언
            return saved;
        }

        @Override
        public Note applyEdit(String slug, LocalDate targetDate, Note draft) {
            Note saved = delegate.applyEdit(slug, targetDate, draft);
            order.add("commit");
            return saved;
        }
    }
}
