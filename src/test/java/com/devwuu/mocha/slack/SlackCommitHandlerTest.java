package com.devwuu.mocha.slack;

import com.devwuu.mocha.domain.Source;
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
import com.devwuu.mocha.llm.AliasGenerator;
import com.devwuu.mocha.llm.PhotoInfoExtractor;
import com.devwuu.mocha.render.NoteRenderer;
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

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TΔ8a(changes/0018): 커밋 경로 이관 후 동작 불변 단언(AC-Δ3 선행 확인) — [저장]/[취소] 버튼의
 * 커밋·렌더·배달·버튼 소진 체인을 flow 3종 미경유의 새 홈 {@link SlackCommitHandler}에 직접 대고 검증한다.
 * 단언 내용은 구 SlackConversationFlowsTest의 커밋 절(T3-5·TΔ2·TΔ3)에서 포팅했다.
 *
 * <p><b>changes/0028 TΔ6d</b>: 저장소가 파일에서 DB로 옮겨지며 픽스처를 인메모리 fake로 바꿨다
 * (구 {@code JsonFileNoteRepository}는 TΔ6a가 폐기). 그와 함께 <b>단언의 층을 정리했다</b> —
 * 병합·별칭 축적(V-13)·날짜 이동(V-10)은 <b>저장소 정책</b>이고 그 검증은 TΔ5b·TΔ5c가 실 Postgres로
 * 소유한다. 여기서 지키는 것은 <b>핸들러가 무엇을 누구에게 넘기는가</b>다: 커밋 인자·호출 순서·파생물
 * 정리·실패 폴백. (모듈 CLAUDE.md §5.2 — 저장 정책을 fake로 흉내내면 테스트가 fake를 검증하게 된다.)
 */
class SlackCommitHandlerTest {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private final Clock clock = Clock.fixed(Instant.parse("2026-07-11T02:00:00Z"), SEOUL);

    private final FakePendingStore pendingStore = new FakePendingStore();
    private final FakeNoteRenderer noteRenderer = new FakeNoteRenderer();
    private final FakeSlackResponder responder = new FakeSlackResponder();
    private final FakePhotoStore photoStore = new FakePhotoStore();
    private final FakePhotoBufferStore photoBufferStore = new FakePhotoBufferStore();
    private final FakeAliasGenerator aliasGenerator = new FakeAliasGenerator();
    private final FakeNoteRepository noteRepository = new FakeNoteRepository();

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
        pendingStore.setPending(newNotePending());

        handler(noteRepository).confirmSave(action(AgentConversationRouter.ACTION_SAVE));

        assertEquals(1, noteRepository.upserts.size(), "커밋이 저장소에 한 번 내려간다");
        assertEquals(1, noteRepository.findAll().size(), "저장된 노트가 조회된다");

        assertEquals(1, pendingStore.clearCount, "커밋 후 pending을 폐기한다");
        assertEquals(List.of("1/2026-07-11"), noteRenderer.entryCards,
                "커밋 후 방금 엔트리 카드만 증분 렌더한다 — 대상은 저장이 발급한 id다");
        assertEquals(0, noteRenderer.renderAllCount, "저장 시점은 전체 리렌더를 트리거하지 않는다");
        assertEquals(1, responder.images.size(), "방금 엔트리 카드 JPG를 채널에 올린다");
        assertEquals(Path.of("cards", "1", "2026-07-11-taste-1.jpg"), responder.images.get(0));
        assertEquals(List.of(MochaMessages.SAVE_DONE_CAPTION), responder.captions);
        assertTrue(responder.messages.isEmpty(), "정상 배달이면 폴백 텍스트가 없다");
    }

    @Test
    @DisplayName("D-1(changes/0028): 신규 노트 [저장]은 noteId=null로 커밋한다 — id는 INSERT가 발급한다")
    void confirmSaveCommitsNewNoteWithoutIdentifier() {
        pendingStore.setPending(newNotePending()); // match=NEW → draft.id()가 없다

        handler(noteRepository).confirmSave(action(AgentConversationRouter.ACTION_SAVE));

        assertNull(noteRepository.upserts.getFirst().noteId(),
                "저장 전 draft는 식별자를 갖지 않으므로 신규 갈래(null)로 내려간다");
    }

    @Test
    @DisplayName("AC-Δ3: 신규 노트 첫 [저장] 커밋 시 생성된 별칭이 저장소로 넘어간다(별칭 1콜 — LLM 개입은 이것뿐)")
    void confirmSavePersistsGeneratedAliasesForNewNote() {
        pendingStore.setPending(newNotePending()); // match=NEW
        aliasGenerator.canned = new Aliases(List.of("커피베라 예가체프"), List.of("커피베라"));

        handler(noteRepository).confirmSave(action(AgentConversationRouter.ACTION_SAVE));

        Aliases committed = noteRepository.upserts.getFirst().aliases();
        assertEquals(List.of("커피베라 예가체프"), committed.coffeeName(), "생성된 커피명 별칭이 커밋 인자로 넘어간다");
        assertEquals(List.of("커피베라"), committed.roastery(), "생성된 로스터리 별칭이 커밋 인자로 넘어간다");
        assertEquals(1, aliasGenerator.calls, "커밋 경로의 LLM 호출은 별칭 생성 1콜뿐(AC-Δ3)");
    }

    @Test
    @DisplayName("plan §7: 별칭 생성 콜 실패 시에도 노트 저장은 성공하고 aliases만 빈 값이다")
    void confirmSaveKeepsCommitWhenAliasGenerationFails() {
        pendingStore.setPending(newNotePending()); // match=NEW
        aliasGenerator.failed = true; // 경계 계약: 콜 실패는 예외가 아니라 빈 별칭 수렴(plan §7, V-13)

        handler(noteRepository).confirmSave(action(AgentConversationRouter.ACTION_SAVE));

        assertEquals(1, noteRepository.upserts.size(), "엔트리는 정상 커밋된다");
        Aliases committed = noteRepository.upserts.getFirst().aliases();
        assertTrue(committed.coffeeName().isEmpty(), "실패 시 커피명 별칭은 빈 배열");
        assertTrue(committed.roastery().isEmpty(), "실패 시 로스터리 별칭은 빈 배열");
        assertEquals(1, pendingStore.clearCount, "커밋은 완료됐다");
    }

    @Test
    @DisplayName("ADR-37: EXISTING 재기록 [저장]은 매칭 노트 id로 커밋하고 별칭 생성 콜을 부르지 않는다")
    void confirmSaveCommitsExistingNoteWithoutAliasCall() {
        // 관측 표기의 무콜 축적(V-13) 자체는 저장소 정책이라 TΔ5b가 실 저장소로 검증한다 —
        // 여기가 지키는 것은 "EXISTING이면 id를 실어 보내고 LLM을 부르지 않는다"는 핸들러 계약이다.
        long noteId = noteRepository.seed(storedNote("Ethiopia Chelbesa", "FroB", LocalDate.of(2026, 7, 1)));
        Entry entry = entry(LocalDate.of(2026, 7, 11), "새콤", OffsetDateTime.now(clock));
        Note draft = new Note(
                noteId, new Sourced<>("에티오피아 첼베사", Source.USER), new Sourced<>("프롭", Source.USER),
                List.of(), null, null, List.of(),
                List.of(entry), OffsetDateTime.now(clock), OffsetDateTime.now(clock));
        pendingStore.setPending(new PendingNote(
                draft, MatchInfo.existing(noteId, LocalDate.of(2026, 7, 11)),
                "1720000000.000999", OffsetDateTime.now(clock)));

        handler(noteRepository).confirmSave(action(AgentConversationRouter.ACTION_SAVE));

        UpsertCall call = noteRepository.upserts.getFirst();
        assertEquals(noteId, call.noteId(), "EXISTING 커밋은 매칭 노트 id로 내려간다(신규 갈래가 아니다)");
        assertEquals("에티오피아 첼베사", Sourced.valueOrNull(call.meta().coffeeName()),
                "이번 기록의 관측 표기가 meta로 넘어간다 — 별칭 축적의 입력(V-13)");
        assertEquals(0, aliasGenerator.calls, "EXISTING 커밋은 별칭 생성 콜이 없다(NEW 전용)");
    }

    @Test
    @DisplayName("AC-Δ6/FR-16: 회차 2개 [저장] → 그 엔트리의 회차 카드 4장 전부를 순서대로 배달한다 — 캡션은 첫 카드에만")
    void confirmSaveDeliversAllBrewCards() {
        pendingStore.setPending(newNotePending());
        noteRenderer.cardSuffixes = List.of("taste-1", "recipe-1", "taste-2", "recipe-2");

        handler(noteRepository).confirmSave(action(AgentConversationRouter.ACTION_SAVE));

        assertEquals(List.of(
                        Path.of("cards", "1", "2026-07-11-taste-1.jpg"),
                        Path.of("cards", "1", "2026-07-11-recipe-1.jpg"),
                        Path.of("cards", "1", "2026-07-11-taste-2.jpg"),
                        Path.of("cards", "1", "2026-07-11-recipe-2.jpg")),
                responder.images, "회차 카드 전부가 렌더 순서 그대로 배달된다");
        assertEquals(MochaMessages.SAVE_DONE_CAPTION, responder.captions.get(0), "캡션은 첫 카드에만 싣는다");
        assertEquals(1, responder.captions.stream().filter(c -> c != null).count(),
                "같은 안내를 카드 수만큼 반복하지 않는다");
        assertTrue(responder.messages.isEmpty(), "전량 배달이면 폴백·부분 안내 텍스트가 없다");
    }

    @Test
    @DisplayName("plan §7/FR-16: 카드 일부 전송 실패 → 저장 유지, 성공분은 배달하고 실패분만 안내한다(부분 폴백)")
    void confirmSaveDeliversSuccessfulCardsOnPartialFailure() {
        pendingStore.setPending(newNotePending());
        noteRenderer.cardSuffixes = List.of("taste-1", "recipe-1", "taste-2", "recipe-2");
        responder.failPaths.add(Path.of("cards", "1", "2026-07-11-recipe-1.jpg"));

        handler(noteRepository).confirmSave(action(AgentConversationRouter.ACTION_SAVE));

        assertEquals(1, noteRepository.findAll().size(), "일부 실패해도 저장은 유지된다");
        assertEquals(List.of(
                        Path.of("cards", "1", "2026-07-11-taste-1.jpg"),
                        Path.of("cards", "1", "2026-07-11-taste-2.jpg"),
                        Path.of("cards", "1", "2026-07-11-recipe-2.jpg")),
                responder.images, "성공분은 배달된다");
        assertEquals(List.of(MochaMessages.SAVE_DONE_PARTIAL_IMAGE), responder.messages,
                "전량 폴백이 아니라 실패분만 안내한다");
        assertEquals(List.of(MochaMessages.FINALIZE_SAVED), responder.finalizeStatuses, "버튼 소진은 그대로 진행된다");
    }

    @Test
    @DisplayName("AC-18: 카드 렌더 실패 → 노트 저장은 유지되고 안내 텍스트로 폴백한다")
    void confirmSaveKeepsCommitWhenRenderFails() {
        pendingStore.setPending(newNotePending());
        noteRenderer.renderFailure = new IllegalStateException("Chromium 미기동");

        handler(noteRepository).confirmSave(action(AgentConversationRouter.ACTION_SAVE));

        assertEquals(1, noteRepository.findAll().size(), "렌더 실패해도 저장은 유지된다(AC-18)");
        assertEquals(1, pendingStore.clearCount, "커밋은 완료됐다");
        assertTrue(responder.images.isEmpty(), "카드는 배달되지 못했다");
        assertEquals(List.of(MochaMessages.SAVE_DONE_NO_IMAGE), responder.messages, "안내 텍스트로 폴백한다");
    }

    @Test
    @DisplayName("AC-18: 카드 전송(files.upload) 실패 → 저장은 유지되고 안내 텍스트로 폴백한다")
    void confirmSaveKeepsCommitWhenUploadFails() {
        pendingStore.setPending(newNotePending());
        responder.imageFailure = new IllegalStateException("files.upload 실패");

        handler(noteRepository).confirmSave(action(AgentConversationRouter.ACTION_SAVE));

        assertEquals(1, noteRepository.findAll().size(), "업로드 실패해도 저장은 유지된다(AC-18)");
        assertEquals(1, noteRenderer.entryCards.size(), "카드는 렌더됐다(전송에서 실패)");
        assertEquals(List.of(MochaMessages.SAVE_DONE_NO_IMAGE), responder.messages, "안내 텍스트로 폴백한다");
    }

    @Test
    @DisplayName("V-7: 만료/부재 pending에 [저장] → 커밋하지 않고 만료 안내 + 스테이징 정리")
    void confirmSaveRejectsWhenNoPending() {
        pendingStore.setPending(null); // get()이 빈 Optional(만료분은 store가 만료 처리 — V-7)

        handler(noteRepository).confirmSave(action(AgentConversationRouter.ACTION_SAVE));

        assertTrue(noteRepository.upserts.isEmpty(), "만료/부재 시 어떤 노트도 저장되지 않는다");
        assertTrue(noteRenderer.entryCards.isEmpty(), "커밋이 없으면 카드 렌더·배달도 없다");
        assertTrue(responder.images.isEmpty());
        assertEquals(1, photoStore.discardCount, "대기 중이던 스테이징 사진도 정리한다(FR-10)");
        assertEquals(List.of(MochaMessages.NOTHING_TO_SAVE), responder.messages);
    }

    @Test
    @DisplayName("AC-4: [취소] → pending 폐기, 어떤 노트도 생성·변경되지 않는다")
    void cancelDiscardsPendingWithoutSaving() {
        pendingStore.setPending(newNotePending());

        handler(noteRepository).cancel(action(AgentConversationRouter.ACTION_CANCEL));

        assertEquals(1, pendingStore.clearCount, "취소는 pending을 폐기한다");
        assertTrue(noteRepository.upserts.isEmpty(), "취소 시 저장은 일어나지 않는다(AC-4)");
        assertTrue(noteRenderer.entryCards.isEmpty());
        assertEquals(List.of(MochaMessages.CANCELED), responder.messages);
    }

    // 손상 pending(엔트리·edit target 결손)의 방어는 저장소 로드 경계로 이관됐다(ADR-66, 0025 TΔ2b) —
    // 소비처 재검증 분기 제거. 훼손 시나리오는 JpaPendingStoreTest의 무결성 테스트가 소유한다.

    // --- 버튼 1회 소진(ADR-20, AC-22) — 구 TΔ2(changes/0009) 절 포팅 ---

    @Test
    @DisplayName("AC-22: [저장] 완료 시 버튼 소진(finalizePreview) 호출 — '저장 완료' 상태 문구로 교체된다")
    void confirmSaveFinalizesPreviewButtons() {
        PendingNote pending = newNotePending();
        pendingStore.setPending(pending);

        handler(noteRepository).confirmSave(action(AgentConversationRouter.ACTION_SAVE));

        assertEquals(List.of(MochaMessages.FINALIZE_SAVED), responder.finalizeStatuses);
        assertEquals(1, responder.finalizePendings.size());
        assertEquals(pending.previewTs(), responder.finalizePendings.get(0).previewTs(),
                "버튼 소진 대상 미리보기 메시지(previewTs)가 넘어가야 한다");
    }

    @Test
    @DisplayName("AC-22: [취소] 완료 시 버튼 소진(finalizePreview) 호출 — '취소됨' 상태 문구로 교체된다")
    void cancelFinalizesPreviewButtons() {
        pendingStore.setPending(newNotePending());

        handler(noteRepository).cancel(action(AgentConversationRouter.ACTION_CANCEL));

        assertEquals(List.of(MochaMessages.FINALIZE_CANCELED), responder.finalizeStatuses);
    }

    @Test
    @DisplayName("V-7: 만료/부재 pending에 [취소] → 버튼 소진 대상이 없어 finalizePreview를 호출하지 않는다")
    void cancelSkipsFinalizeWhenNoPending() {
        pendingStore.setPending(null);

        handler(noteRepository).cancel(action(AgentConversationRouter.ACTION_CANCEL));

        assertTrue(responder.finalizeStatuses.isEmpty(), "갱신할 미리보기가 없으면 버튼 소진을 호출하지 않는다");
        assertEquals(List.of(MochaMessages.CANCELED), responder.messages);
    }

    @Test
    @DisplayName("ADR-20: 버튼 소진(chat.update) 실패를 주입해도 노트 커밋·카드 배달은 정상 완료된다(로그만)")
    void confirmSaveKeepsCommitAndDeliveryWhenFinalizeFails() {
        pendingStore.setPending(newNotePending());
        responder.finalizeFailure = new IllegalStateException("chat.update 실패");

        handler(noteRepository).confirmSave(action(AgentConversationRouter.ACTION_SAVE));

        assertEquals(1, noteRepository.findAll().size(), "버튼 소진 실패해도 저장은 유지된다");
        assertEquals(1, pendingStore.clearCount, "커밋은 완료됐다");
        assertEquals(1, responder.images.size(), "카드 배달도 정상 완료된다");
        assertTrue(responder.messages.isEmpty(), "정상 배달이면 폴백 텍스트가 없다");
    }

    @Test
    @DisplayName("AC-Δ3(회귀 가드): [저장] 커밋 → 사진 아카이브 → pending clear → 카드 배달 → 버튼 소진 순서")
    void confirmSavePreservesCommitFlowOrder() {
        // 사진 커밋이 저장 '뒤'인 것이 changes/0028의 순서다 — 폴더 접미의 앞자리가 id인데 신규 노트의
        // id는 INSERT가 발급한다(§파일 경로 규약, TΔ9b). 이 단언이 그 위치를 붙잡는다.
        List<String> order = new ArrayList<>();
        noteRepository.order = order;
        pendingStore.order = order;
        responder.order = order;
        photoStore.order = order;
        pendingStore.setPending(newNotePending());

        handler(noteRepository).confirmSave(action(AgentConversationRouter.ACTION_SAVE));

        assertEquals(List.of("commit", "photo", "clear", "deliver", "finalize"), order,
                "저장 커밋 → 사진 아카이브 → pending clear → 카드 배달 → 버튼 소진 순서가 유지되어야 한다");
    }

    // --- 사진 커밋(FR-10, changes/0014 ADR-32) ---

    @Test
    @DisplayName("ADR-32/AC-Δ9: [저장] 시 스테이징 사진을 photos/<id>-<로스터리>-<커피명>/<date>/로 아카이브 커밋한다")
    void confirmSaveArchivesStagedPhotos() {
        photoStore.staged.add("a.jpg"); // 대기 중 스테이징 사진
        pendingStore.setPending(newNotePending()); // 엔트리 date=2026-07-11

        handler(noteRepository).confirmSave(action(AgentConversationRouter.ACTION_SAVE));

        assertEquals(List.of("photos/1-커피베라-커피베라-예가체프/2026-07-11/a.jpg"), photoStore.committed,
                "폴더 접미는 저장이 발급한 id로 시작한다 — 신규 노트는 커밋 전에 알 수 없는 값이다");
        assertTrue(photoStore.staged.isEmpty(), "commit 후 스테이징은 비워진다");
        assertEquals(1, noteRepository.findAll().size(), "엔트리는 저장된다");
    }

    @Test
    @DisplayName("AC-4/FR-10: [취소] 시 대기 중이던 스테이징 사진·버퍼도 함께 폐기된다")
    void cancelDiscardsStagedPhotos() {
        photoStore.staged.add("a.jpg");
        pendingStore.setPending(newNotePending());

        handler(noteRepository).cancel(action(AgentConversationRouter.ACTION_CANCEL));

        assertEquals(1, photoStore.discardCount, "취소는 스테이징 사진을 폐기한다");
        assertTrue(photoStore.staged.isEmpty());
        assertEquals(1, photoBufferStore.clearCount, "사진 버퍼도 함께 정리한다");
    }

    // --- edit 커밋(FR-21, changes/0012) — 구 TΔ3 절 포팅 ---

    @Test
    @DisplayName("AC-Δ3: edit [저장] 날짜 이동 → applyEdit 커밋 후 옛 date 카드 삭제 → 새 date 카드 증분 렌더·배달")
    void confirmSaveEditMovesDateAndCleansOldCard() {
        long noteId = seedEditableNote(LocalDate.of(2026, 7, 8));
        pendingStore.setPending(editPending(noteId, LocalDate.of(2026, 7, 8), LocalDate.of(2026, 7, 9), "고친 감상"));

        handler(noteRepository).confirmSave(action(AgentConversationRouter.ACTION_SAVE));

        // 이동 결과(엔트리 총수 불변·날짜 이동, V-10)는 저장소 정책이라 TΔ5c가 검증한다 — 여기서는 인자다.
        EditCall call = noteRepository.edits.getFirst();
        assertEquals(noteId, call.noteId(), "수정 대상 노트 id로 커밋한다");
        assertEquals(LocalDate.of(2026, 7, 8), call.targetDate(), "원본 엔트리 date가 이동 기준으로 넘어간다");
        assertEquals(LocalDate.of(2026, 7, 9), call.draft().entries().getFirst().date(), "draft 엔트리는 새 date다");
        assertEquals("고친 감상", tasteOf(call.draft().entries().getFirst()), "수정 내용이 draft에 실린다");

        assertEquals(1, pendingStore.clearCount, "커밋 후 pending 폐기");
        assertEquals(List.of(NOTE_FOLDER + "/2026-07-08→2026-07-09"), photoStore.moves, "사진 폴더도 새 date로 이동");
        assertEquals(List.of(noteId + "/2026-07-08"), noteRenderer.removedCards, "옛 date 카드 삭제");
        assertEquals(List.of(noteId + "/2026-07-09"), noteRenderer.entryCards, "새 date 카드만 증분 렌더");
        assertEquals(0, noteRenderer.renderAllCount, "edit 저장도 전체 리렌더를 트리거하지 않는다");
        assertEquals(List.of(Path.of("cards", String.valueOf(noteId), "2026-07-09-taste-1.jpg")),
                responder.images, "갱신 카드 배달");
        assertEquals(List.of(MochaMessages.FINALIZE_SAVED), responder.finalizeStatuses, "버튼 1회 소진");
        assertTrue(responder.messages.isEmpty(), "정상 배달이면 폴백 텍스트가 없다");
    }

    @Test
    @DisplayName("AC-Δ3: edit [저장] 날짜 유지 → 옛 카드 삭제 없이 해당 date 카드만 다시 굽는다")
    void confirmSaveEditWithoutDateMoveSkipsCardRemoval() {
        long noteId = seedEditableNote(LocalDate.of(2026, 7, 8));
        pendingStore.setPending(editPending(noteId, LocalDate.of(2026, 7, 8), LocalDate.of(2026, 7, 8), "고친 감상"));

        handler(noteRepository).confirmSave(action(AgentConversationRouter.ACTION_SAVE));

        assertEquals("고친 감상", tasteOf(noteRepository.edits.getFirst().draft().entries().getFirst()),
                "필드 갱신이 draft로 넘어간다");
        assertTrue(noteRenderer.removedCards.isEmpty(), "날짜가 그대로면 카드 삭제가 없다(같은 경로 덮어쓰기)");
        assertTrue(photoStore.moves.isEmpty(), "날짜가 그대로면 사진 폴더 이동도 없다");
        assertEquals(List.of(noteId + "/2026-07-08"), noteRenderer.entryCards, "해당 date 카드 재렌더");
    }

    @Test
    @DisplayName("ADR-32/plan §7: 사진 폴더 이동 실패 → 커밋 유지, 새 카드 렌더·배달은 그대로 진행된다(best-effort)")
    void confirmSaveEditKeepsCommitWhenPhotoMoveFails() {
        long noteId = seedEditableNote(LocalDate.of(2026, 7, 8));
        pendingStore.setPending(editPending(noteId, LocalDate.of(2026, 7, 8), LocalDate.of(2026, 7, 9), "고친 감상"));
        photoStore.moveFailure = new IllegalStateException("사진 폴더 이동 실패");

        handler(noteRepository).confirmSave(action(AgentConversationRouter.ACTION_SAVE));

        assertEquals(1, noteRepository.edits.size(), "이동 실패해도 수정 커밋은 유지된다");
        assertEquals(List.of(noteId + "/2026-07-08"), noteRenderer.removedCards, "옛 카드 삭제는 그대로 진행");
        assertEquals(List.of(noteId + "/2026-07-09"), noteRenderer.entryCards, "새 카드 렌더도 그대로 진행");
        assertEquals(1, responder.images.size(), "카드 배달도 그대로 진행");
        assertTrue(responder.messages.isEmpty(), "이동 실패만으로 폴백 텍스트를 보내지 않는다(로그만)");
    }

    @Test
    @DisplayName("plan §7: 옛 카드 삭제 실패 → 커밋 유지, 새 카드 렌더·배달은 그대로 진행된다")
    void confirmSaveEditKeepsCommitWhenCardRemovalFails() {
        long noteId = seedEditableNote(LocalDate.of(2026, 7, 8));
        pendingStore.setPending(editPending(noteId, LocalDate.of(2026, 7, 8), LocalDate.of(2026, 7, 9), "고친 감상"));
        noteRenderer.removeFailure = new IllegalStateException("카드 삭제 실패");

        handler(noteRepository).confirmSave(action(AgentConversationRouter.ACTION_SAVE));

        assertEquals(1, noteRepository.edits.size(), "커밋은 유지된다");
        assertEquals(List.of(noteId + "/2026-07-09"), noteRenderer.entryCards, "새 카드 렌더는 그대로 진행");
        assertEquals(1, responder.images.size(), "카드 배달도 그대로 진행");
        assertTrue(responder.messages.isEmpty(), "삭제 실패만으로 폴백 텍스트를 보내지 않는다(로그만)");
    }

    @Test
    @DisplayName("plan §7: edit 커밋 후 카드 렌더 실패 → 커밋은 유지되고 안내 텍스트로 폴백한다")
    void confirmSaveEditKeepsCommitWhenRenderFails() {
        long noteId = seedEditableNote(LocalDate.of(2026, 7, 8));
        pendingStore.setPending(editPending(noteId, LocalDate.of(2026, 7, 8), LocalDate.of(2026, 7, 9), "고친 감상"));
        noteRenderer.renderFailure = new IllegalStateException("Chromium 미기동");

        handler(noteRepository).confirmSave(action(AgentConversationRouter.ACTION_SAVE));

        assertEquals(1, noteRepository.edits.size(), "렌더 실패해도 수정 커밋은 유지(AC-18 준용)");
        assertTrue(responder.images.isEmpty(), "카드는 배달되지 못했다");
        assertEquals(List.of(MochaMessages.SAVE_DONE_NO_IMAGE), responder.messages, "안내 텍스트로 폴백");
        assertEquals(List.of(MochaMessages.FINALIZE_SAVED), responder.finalizeStatuses, "버튼 소진은 그대로");
    }

    @Test
    @DisplayName("V-7 준용: [저장] 시 수정 대상 소실 → 커밋 없이 만료 안내 + pending·스테이징 정리")
    void confirmSaveEditRejectsWhenTargetGone() {
        // 대상 노트를 심지 않는다 — 소실 상황(findById가 빈 Optional).
        pendingStore.setPending(editPending(999L, LocalDate.of(2026, 7, 8), LocalDate.of(2026, 7, 9), "고친 감상"));

        handler(noteRepository).confirmSave(action(AgentConversationRouter.ACTION_SAVE));

        assertTrue(noteRepository.edits.isEmpty(), "대상 소실 시 어떤 커밋도 일어나지 않는다");
        assertTrue(noteRenderer.entryCards.isEmpty() && noteRenderer.removedCards.isEmpty(), "파생물 접촉 없음");
        assertTrue(photoStore.committed.isEmpty(), "사진 아카이브 커밋도 없다 — 폴더를 확정할 노트가 없다");
        assertEquals(1, pendingStore.clearCount, "죽은 edit pending은 폐기한다");
        assertEquals(1, photoStore.discardCount, "스테이징 사진도 만료 경로처럼 정리한다");
        assertEquals(List.of(MochaMessages.NOTHING_TO_SAVE), responder.messages, "만료 안내로 수렴(V-7 준용)");
    }

    @Test
    @DisplayName("ADR-32: edit [저장] 시 스테이징된 새 사진이 대상 엔트리 날짜의 아카이브 폴더로 커밋된다")
    void confirmSaveEditArchivesStagedPhotos() {
        long noteId = seedEditableNote(LocalDate.of(2026, 7, 8));
        photoStore.stage("U1", "b.jpg", new byte[]{1});
        pendingStore.setPending(editPending(noteId, LocalDate.of(2026, 7, 8), LocalDate.of(2026, 7, 9), "고친 감상"));

        handler(noteRepository).confirmSave(action(AgentConversationRouter.ACTION_SAVE));

        assertEquals(List.of("photos/" + NOTE_FOLDER + "/2026-07-09/b.jpg"), photoStore.committed,
                "스테이징 사진이 대상 엔트리 날짜 폴더로 커밋된다");
        assertTrue(photoStore.staged.isEmpty(), "커밋 후 스테이징은 비워진다");
        assertEquals(1, noteRepository.edits.size(), "엔트리는 저장되되 사진 필드는 없다");
    }

    @Test
    @DisplayName("changes/0028 §파일 경로 규약: edit 사진 폴더는 draft가 아니라 저장된 원본 노트로 만든다(생성 시점 스냅샷)")
    void editPhotoFolderComesFromStoredNoteNotDraft() {
        // draft의 로스터리를 이번 세션에서 고쳐도 사진 폴더는 저장된 이름을 따른다 — 폴더를 이동하지 않는
        // 것이 delta 파생 결정 2이고, draft로 계산하면 수정 즉시 아카이브가 갈린다.
        long noteId = seedEditableNote(LocalDate.of(2026, 7, 8));
        photoStore.stage("U1", "b.jpg", new byte[]{1});
        PendingNote pending = editPending(noteId, LocalDate.of(2026, 7, 8), LocalDate.of(2026, 7, 8), "고친 감상");
        Note draft = pending.draft();
        Note renamed = new Note(draft.id(), draft.coffeeName(), new Sourced<>("이름바뀐로스터리", Source.USER),
                draft.beans(), draft.roastLevel(), draft.officialNotes(), draft.sources(),
                draft.entries(), draft.createdAt(), draft.updatedAt());
        pendingStore.setPending(new PendingNote(PendingNote.Mode.EDIT, renamed, pending.target(),
                null, pending.previewTs(), pending.createdAt()));

        handler(noteRepository).confirmSave(action(AgentConversationRouter.ACTION_SAVE));

        assertEquals(List.of("photos/" + NOTE_FOLDER + "/2026-07-08/b.jpg"), photoStore.committed,
                "폴더 접미는 저장된 노트의 로스터리로 만들어진다(수정 후 값이 아니다)");
    }

    // 손상 edit pending(target 결손) 방어도 저장소 로드 경계로 이관됐다(ADR-66, 0025 TΔ2b) —
    // JpaPendingStoreTest.corruptEditWithoutTargetIsDiscarded가 소유한다.

    // ---- 헬퍼 ----

    /** 심는 노트(id=1 · 로스터리 "커피베라" · 커피명 "커피베라 예가체프")의 폴더 접미 — NoteFolderName 규칙. */
    private static final String NOTE_FOLDER = "1-커피베라-커피베라-예가체프";

    // 회차 구조(changes/0021 ADR-59) 픽스처·접근 헬퍼 — 이 테스트의 엔트리는 회차 1개 전제.
    private static Entry entry(LocalDate date, String taste, OffsetDateTime ts) {
        return new Entry(date, List.of(new Brew(null, new Tasting(taste, null, Rating.GOOD))), ts);
    }

    private static String tasteOf(Entry entry) {
        return entry.brews().getFirst().tasting().myTaste();
    }

    /** match=NEW record pending — 저장 전이라 draft에 식별자가 없다(D-1). */
    private static PendingNote newNotePending() {
        Entry entry = entry(LocalDate.of(2026, 7, 11), "새콤하고 좋았다", OffsetDateTime.now());
        Note draft = new Note(
                null, new Sourced<>("커피베라 예가체프", Source.USER),
                new Sourced<>("커피베라", Source.USER), List.of(new Bean(new Sourced<>("에티오피아", Source.SEARCH), null)), null,
                null, List.of("https://example.com/coffee"),
                List.of(entry), OffsetDateTime.now(), OffsetDateTime.now());
        return new PendingNote(draft, MatchInfo.newNote(), "1720000000.000999", OffsetDateTime.now());
    }

    // mode=edit pending — 원본 (noteId, targetDate) 엔트리를 newDate·새 감상으로 고치는 단일 엔트리 draft.
    private static PendingNote editPending(long noteId, LocalDate targetDate, LocalDate newDate, String myTaste) {
        Entry entry = entry(newDate, myTaste, OffsetDateTime.now());
        Note draft = new Note(
                noteId, new Sourced<>("커피베라 예가체프", Source.USER),
                new Sourced<>("커피베라", Source.USER), List.of(new Bean(new Sourced<>("에티오피아", Source.SEARCH), null)), null,
                null, List.of(), List.of(entry), OffsetDateTime.now(), OffsetDateTime.now());
        return new PendingNote(PendingNote.Mode.EDIT, draft, new PendingNote.EditTarget(noteId, targetDate),
                null, "1720000000.000999", OffsetDateTime.now());
    }

    /** 수정 대상이 될 원본 노트(엔트리 1건)를 저장소에 심고 발급된 id를 돌려준다. */
    private long seedEditableNote(LocalDate date) {
        return noteRepository.seed(storedNote("커피베라 예가체프", "커피베라", date));
    }

    private static Note storedNote(String coffeeName, String roastery, LocalDate date) {
        return new Note(
                null, new Sourced<>(coffeeName, Source.USER), new Sourced<>(roastery, Source.USER),
                List.of(new Bean(new Sourced<>("에티오피아", Source.SEARCH), null)), null, null, List.of(),
                List.of(entry(date, "원래 감상", OffsetDateTime.now())),
                OffsetDateTime.now(), OffsetDateTime.now());
    }

    private static IncomingAction action(String actionId) {
        return new IncomingAction("U1", "C1", actionId);
    }

    // ---- fakes (모듈 CLAUDE.md §5.2 — 외부 의존은 인터페이스 stub/fake, 구 SlackConversationFlowsTest에서 포팅) ----

    /** upsertEntry 호출 인자 — 핸들러가 저장소에 무엇을 넘겼는지가 이 테스트의 검증 대상이다. */
    private record UpsertCall(Long noteId, NoteMeta meta, Entry entry, Aliases aliases) {
    }

    /** applyEdit 호출 인자. */
    private record EditCall(long noteId, LocalDate targetDate, Note draft) {
    }

    /**
     * 인메모리 노트 저장소 fake — 저장된 노트를 돌려주고 커밋 인자를 캡처한다.
     * <p><b>저장 정책은 흉내내지 않는다</b>: 같은 날 병합(ADR-4·59)·별칭 축적(V-13)·날짜 이동(V-10)은
     * {@code JpaNoteRepository}의 몫이고 TΔ5b·TΔ5c가 실 Postgres로 검증한다. 여기서 그 정책을 재구현하면
     * 테스트가 fake를 검증하게 된다.
     */
    private static final class FakeNoteRepository implements NoteRepository {
        private final Map<Long, Note> notes = new LinkedHashMap<>();
        final List<UpsertCall> upserts = new ArrayList<>();
        final List<EditCall> edits = new ArrayList<>();
        private long nextId = 1;
        List<String> order; // 커밋 흐름 순서 회귀 가드용 공용 로그(비면 무시).

        /** 저장된 노트를 심고 발급된 id를 돌려준다 — 실 저장소의 BIGSERIAL 발급 자리. */
        long seed(Note note) {
            long id = nextId++;
            notes.put(id, withId(note, id));
            return id;
        }

        @Override
        public List<Note> findAll() {
            return List.copyOf(notes.values()); // LinkedHashMap = id 발급 순 = id 오름차순
        }

        @Override
        public Optional<Note> findById(long id) {
            return Optional.ofNullable(notes.get(id));
        }

        @Override
        public Note upsertEntry(Long noteId, NoteMeta meta, Entry entry, Aliases aliases) {
            upserts.add(new UpsertCall(noteId, meta, entry, aliases));
            long id = noteId != null ? noteId : nextId++;
            Note saved = new Note(id, meta.coffeeName(), meta.roastery(), meta.beans(), meta.roastLevel(),
                    meta.officialNotes(), aliases, meta.sources(), List.of(entry),
                    OffsetDateTime.now(), OffsetDateTime.now());
            notes.put(id, saved);
            if (order != null) {
                order.add("commit");
            }
            return saved;
        }

        @Override
        public Note applyEdit(long noteId, LocalDate targetDate, Note draft) {
            edits.add(new EditCall(noteId, targetDate, draft));
            Note saved = withId(draft, noteId);
            notes.put(noteId, saved);
            if (order != null) {
                order.add("commit");
            }
            return saved;
        }

        // 삭제는 Slack 커밋 흐름에 없다 — 노출은 A2(수정 화면의 삭제 버튼)다. 호출되면 그 자체가 회귀다.
        @Override
        public void delete(long id) {
            throw new UnsupportedOperationException("커밋 핸들러는 노트를 지우지 않는다 — 삭제 노출은 A2 범위다");
        }

        private static Note withId(Note note, long id) {
            return new Note(id, note.coffeeName(), note.roastery(), note.beans(), note.roastLevel(),
                    note.officialNotes(), note.aliases(), note.sources(), note.entries(),
                    note.createdAt(), note.updatedAt());
        }
    }

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

    /** renderAll 호출과 증분 renderEntryCard/removeEntryCard 호출(noteId/date)을 캡처하고, 실패를 주입하는 fake. */
    private static final class FakeNoteRenderer implements NoteRenderer {
        int renderAllCount = 0;
        final List<String> entryCards = new ArrayList<>(); // "noteId/date" 캡처
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
        public List<Path> renderEntryCard(long noteId, LocalDate date) {
            if (renderFailure != null) {
                throw renderFailure;
            }
            entryCards.add(noteId + "/" + date);
            // 회차화(changes/0021 TΔ5a) 산출 형태 — CardFiles.expectedCards 순서(회차 오름차순, 감상 → 레시피).
            // 실 렌더러는 폴더 접미를 노트에서 만들지만(TΔ6c) fake는 id만 알므로 경로도 id로 둔다.
            return cardSuffixes.stream()
                    .map(suffix -> Path.of("cards", String.valueOf(noteId), date + "-" + suffix + ".jpg"))
                    .toList();
        }

        @Override
        public void removeEntryCard(long noteId, LocalDate date) {
            if (removeFailure != null) {
                throw removeFailure;
            }
            removedCards.add(noteId + "/" + date);
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
        final List<String> moves = new ArrayList<>(); // moveEntryPhotos "<접미>/from→to" 캡처
        RuntimeException moveFailure = null;
        int discardCount = 0;
        List<String> order; // 커밋 흐름 순서 회귀 가드용 공용 로그(비면 무시).

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
        public List<String> commit(String userId, String noteFolder, String date) {
            List<String> paths = staged.stream().map(n -> "photos/" + noteFolder + "/" + date + "/" + n).toList();
            committed.addAll(paths);
            staged.clear();
            stagedBytes.clear();
            if (order != null) {
                order.add("photo");
            }
            return paths;
        }

        @Override
        public void discard(String userId) {
            staged.clear();
            stagedBytes.clear();
            discardCount++;
        }

        @Override
        public void moveEntryPhotos(String noteFolder, String fromDate, String toDate) {
            if (moveFailure != null) {
                throw moveFailure;
            }
            moves.add(noteFolder + "/" + fromDate + "→" + toDate);
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
}
