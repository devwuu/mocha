package com.devwuu.mocha.slack;

import com.devwuu.mocha.domain.Aliases;
import com.devwuu.mocha.domain.Entry;
import com.devwuu.mocha.domain.MatchInfo;
import com.devwuu.mocha.domain.Note;
import com.devwuu.mocha.domain.NoteMeta;
import com.devwuu.mocha.domain.PendingNote;
import com.devwuu.mocha.domain.Sourced;
import com.devwuu.mocha.llm.AliasGenerator;
import com.devwuu.mocha.render.NoteRenderer;
import com.devwuu.mocha.repository.NoteRepository;
import com.devwuu.mocha.repository.PendingStore;
import com.devwuu.mocha.slack.inbound.IncomingAction;
import com.devwuu.mocha.slack.inbound.SlackPhotoIntake;
import com.devwuu.mocha.slack.outbound.MochaMessages;
import com.devwuu.mocha.slack.outbound.SlackResponder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * [저장]/[취소] 버튼 커밋 핸들러 — 커밋·렌더·배달·버튼 소진 체인의 독립된 홈
 * (ref: specs/coffee-note-agent/plan.md#ADR-3·ADR-20·ADR-45, changes/0018 TΔ8a).
 * <p>구 record/edit flow의 커밋 체인에서 로직 변경 없이 이관했다(findings-TΔ0 §2) — 라우터가
 * 오케스트레이션을 거치지 않고 직접 부른다(delta AC-Δ3).
 * pending 로드·TTL 판정(V-7)·결손 검증은 mode와 무관하게 {@link #confirmSave}가 공통 게이트로 소유하고,
 * mode=edit 커밋만 내부 분기로 갈린다.
 * <p>이름에 Slack을 붙인 이유: {@link IncomingAction} 수신·카드 배달·버튼 소진({@link SlackResponder}) 등
 * Slack 전송 계층에 결합된 구체 핸들러다({@link SlackPhotoIntake}과 동일 규칙). 조립은 config 빈 배선이
 * 소유한다(ADR-63, RouterConfig) — 공개 승격 이유는 Clock 빈과 동일(config가 타 패키지).
 * <p>POLICY: 사용자 [저장] 확인 없이 {@link NoteRepository} 쓰기를 호출하지 않는다 (ref: plan.md#ADR-3, AC-4).
 * <p>POLICY: [저장] 버튼 처리에 LLM 호출은 별칭 생성 1콜(match=NEW 첫 커밋 한정) 외에 없다
 * (ref: specs/coffee-note-agent/changes/0018/delta.md#AC-Δ3).
 */
public class SlackCommitHandler {

    private static final Logger log = LoggerFactory.getLogger(SlackCommitHandler.class);

    private final PendingStore pendingStore;
    private final NoteRepository noteRepository;
    private final NoteRenderer noteRenderer;
    private final SlackResponder responder;
    private final AliasGenerator aliasGenerator;
    private final SlackPhotoIntake photoIntake;

    public SlackCommitHandler(
            PendingStore pendingStore,
            NoteRepository noteRepository,
            NoteRenderer noteRenderer,
            SlackResponder responder,
            AliasGenerator aliasGenerator,
            SlackPhotoIntake photoIntake) {
        this.pendingStore = pendingStore;
        this.noteRepository = noteRepository;
        this.noteRenderer = noteRenderer;
        this.responder = responder;
        this.aliasGenerator = aliasGenerator;
        this.photoIntake = photoIntake;
    }

    /**
     * [저장] 버튼 커밋. pending 로드·TTL 판정(V-7)·결손 검증은 mode와 무관하게 여기가 공통 게이트고,
     * mode=edit 커밋만 {@link #commitEdit}로 갈린다.
     */
    void confirmSave(IncomingAction action) {
        String userId = action.userId();
        // V-7: pending 부재/TTL 초과면 저장하지 않고 만료 안내 — get()이 만료분을 빈 Optional로 준다.
        Optional<PendingNote> pendingOpt = pendingStore.get(userId);
        if (pendingOpt.isEmpty()) {
            log.info("[저장] 무효 — pending 부재/만료: user={}", userId);
            // 만료/부재면 대기 중이던 스테이징 사진도 버려진 것 — 노트 트리로 새지 않게 정리한다(FR-10).
            photoIntake.discard(userId);
            responder.post(action.channelId(), MochaMessages.NOTHING_TO_SAVE);
            return;
        }

        // previewTs는 pending clear 이후엔 다시 못 읽으므로 clear 전에 지역 변수로 확보한다(버튼 소진 대상, findings-TΔ0 §1).
        PendingNote pending = pendingOpt.get();
        Note draft = pending.draft();
        String slug = draft.slug();
        Entry entry = latestEntry(draft);
        boolean editMode = pending.mode() == PendingNote.Mode.EDIT;
        // 커밋 대상은 draft.slug()(신규는 제안 시점에 할당, 기존은 매칭 slug). 결손이면 저장하지 않는다.
        // edit 모드는 갱신 대상 참조(target)도 필수다(data-model §2.3).
        if (slug == null || slug.isBlank() || entry == null || (editMode && pending.target() == null)) {
            log.warn("[저장] 무효 — 손상된 pending(slug/entry/target 결손): user={} slug={}", userId, slug);
            responder.post(action.channelId(), MochaMessages.BROKEN_PENDING);
            return;
        }

        // edit 커밋은 별도 경로 — 신규 기록(record) 흐름은 mode 도입 전과 동일하게 유지한다(delta AC-Δ6).
        if (editMode) {
            commitEdit(action, pending, entry);
            return;
        }

        // 사진 커밋: 스테이징 원본을 photos/<slug>/<date>/로 아카이브 이동한다(FR-10, changes/0014 ADR-32).
        // 로컬 move라 저장 커밋 경계 안에서 수행한다(외부 I/O는 수신 시점에 이미 끝남, CLAUDE.md §3).
        // 반환 경로는 노트에 싣지 않는다 — 사진은 아카이브 전용, JSON 기록 없음(delta AC-Δ1).
        String date = entry.date().toString();
        photoIntake.commitStaged(userId, slug, date);
        Entry committedEntry = entry;

        // 신규 노트(match=NEW) 첫 커밋에 한해 별칭을 1콜로 생성한다(노트당 평생 1회 — 관측 축적은 TΔ3).
        // POLICY: 외부 호출은 파일 쓰기 전에 끝낸다(CLAUDE.md §3). 생성 실패는 저장을 되돌리지 않는다 —
        //         빈 별칭으로 수렴(AliasGenerator 내부 처리) (ref: plan.md#ADR-37, §7, V-13).
        Aliases aliases = pending.match() != null && pending.match().type() == MatchInfo.MatchType.NEW
                ? aliasGenerator.generate(
                        Sourced.valueOrNull(draft.coffeeName()), Sourced.valueOrNull(draft.roastery()))
                : Aliases.empty();

        // POLICY: 사용자 [저장] 확인을 거친 뒤에만 저장한다 (ref: plan.md#ADR-3, AC-4).
        Note saved = noteRepository.upsertEntry(slug, metaOf(draft), committedEntry, aliases);
        pendingStore.clear(userId);
        photoIntake.clearBuffer(userId);
        log.info("[저장] 커밋 완료: slug={} entries={}", saved.slug(), saved.entries().size());

        // 저장은 이미 커밋됨 — 카드 렌더·전송 실패는 데이터 손실이 아니다. 실패해도 저장은 유지하고 안내 텍스트로 폴백한다.
        deliverEntryCards(action.channelId(), slug, committedEntry);

        // 커밋·배달 이후 미리보기 버튼을 1회 소진한다 — 실패해도 저장·배달 결과는 유지된다(ADR-20, AC-Δ2).
        finalizePreviewQuietly(action.channelId(), pending, MochaMessages.FINALIZE_SAVED);
    }

    /** [취소] 버튼 — 저장 없이 pending만 폐기한다(AC-4). */
    void cancel(IncomingAction action) {
        // 버튼 소진에 previewTs가 필요하므로 clear 이전에 pending을 읽어 확보한다(findings-TΔ0 §2).
        Optional<PendingNote> pendingOpt = pendingStore.get(action.userId());
        // [취소]는 저장 없이 pending만 폐기한다(AC-4). 대기 중이던 스테이징 사진·버퍼도 함께 정리한다(FR-10).
        pendingStore.clear(action.userId());
        photoIntake.discard(action.userId());
        log.info("[취소] pending 폐기: user={}", action.userId());
        responder.post(action.channelId(), MochaMessages.CANCELED);

        // 취소 안내 이후 미리보기 버튼을 1회 소진한다 — pending이 있었을 때만(만료/부재면 갱신 대상 없음).
        pendingOpt.ifPresent(pending -> finalizePreviewQuietly(action.channelId(), pending, MochaMessages.FINALIZE_CANCELED));
    }

    // edit 커밋(FR-21, AC-37·39, changes/0012 TΔ3) — [저장] 확답 시 대상 노트를 draft로 갱신하고 파생물을 정리한다.
    // pending 로드·TTL·결손 검증은 공통 게이트(confirmSave)가 이미 끝냈다.
    private void commitEdit(IncomingAction action, PendingNote pending, Entry entry) {
        String userId = action.userId();
        PendingNote.EditTarget target = pending.target();
        String slug = target.slug();

        // V-7 준용: [저장] 시점에 수정 대상(노트/엔트리)이 소실됐으면 커밋 없이 만료 안내로 수렴한다(plan §7).
        // 죽은 세션이므로 만료 경로와 동일하게 pending·스테이징 사진을 정리한다.
        Optional<Entry> origin = noteRepository.findBySlug(slug)
                .flatMap(note -> note.entries().stream()
                        .filter(e -> e.date().equals(target.date())).findFirst());
        if (origin.isEmpty()) {
            log.warn("[저장] 무효 — 수정 대상 소실: user={} slug={} date={}", userId, slug, target.date());
            pendingStore.clear(userId);
            photoIntake.discard(userId);
            responder.post(action.channelId(), MochaMessages.NOTHING_TO_SAVE);
            return;
        }

        // 수정 중 스테이징된 새 사진을 대상 엔트리 날짜의 아카이브 폴더로 이동한다(AC-Δ5 = spec AC-41).
        // 반환 경로는 노트에 싣지 않는다 — 사진은 아카이브 전용, JSON 기록 없음(changes/0014 ADR-32).
        String date = entry.date().toString();
        photoIntake.commitStaged(userId, slug, date);
        Entry committedEntry = entry;

        // POLICY: 사용자 [저장] 확인을 거친 뒤에만 저장한다 (ref: plan.md#ADR-3, AC-37).
        Note saved = noteRepository.applyEdit(slug, target.date(), withLatestEntry(pending.draft(), committedEntry));
        pendingStore.clear(userId);
        photoIntake.clearBuffer(userId);
        boolean dateMoved = !target.date().equals(entry.date());
        log.info("[저장] 수정 커밋 완료: slug={} {} → {} entries={}",
                slug, target.date(), entry.date(), saved.entries().size());

        // 파생물 정리: 날짜 이동이면 옛 date 카드부터 지운다. 삭제 실패는 커밋을 되돌리지 않고 새 카드 렌더로
        // 계속 진행한다 — 남은 옛 카드는 renderAll(--rerender)이 정리한다(plan §7, AC-39).
        if (dateMoved) {
            // POLICY: 날짜 이동 시 사진 폴더 이동은 best-effort — 실패해도 커밋을 되돌리지 않는다(사진은 옛 폴더
            //         잔류, 아카이브로서 유효). 옛 카드 삭제와 동일 정책 (ref: plan.md#ADR-32, §7, FR-21).
            try {
                photoIntake.moveEntryPhotos(slug, target.date().toString(), entry.date().toString());
            } catch (RuntimeException e) {
                log.warn("사진 폴더 이동 실패(수정은 저장됨, 사진은 옛 폴더 잔류): slug={} {} → {}",
                        slug, target.date(), entry.date(), e);
            }
            try {
                noteRenderer.removeEntryCard(slug, target.date());
            } catch (RuntimeException e) {
                log.warn("옛 카드 삭제 실패(수정은 저장됨, --rerender로 정리 가능): slug={} date={}",
                        slug, target.date(), e);
            }
        }
        // 새 date 회차 카드 증분 렌더 → 갱신 카드 배달(AC-37).
        deliverEntryCards(action.channelId(), slug, committedEntry);

        // 커밋·배달 이후 미리보기 버튼을 1회 소진한다(0009 재사용) — 실패해도 저장·배달 결과는 유지된다(ADR-20).
        finalizePreviewQuietly(action.channelId(), pending, MochaMessages.FINALIZE_SAVED);
    }

    // 커밋 직후 카드 배달 — 방금 그 엔트리의 회차 카드 전부를 순서대로 배달한다(FR-16, changes/0021 TΔ5b).
    // POLICY: 저장 시점 렌더는 증분 — 방금 그 (slug,date) 엔트리의 회차 카드만 굽는다(전체 재래스터화는 --rerender)
    //         (ref: plan.md#ADR-10·ADR-59, AC-Δ7).
    // POLICY: 카드 이미지 생성·전송 실패는 저장을 되돌리지 않는다 — 렌더 실패는 안내 텍스트로 폴백하고,
    //         전송은 일부만 실패하면 성공분은 배달하며 실패분만 안내한다(부분 폴백) (ref: plan.md §7, AC-18, FR-16).
    private void deliverEntryCards(String channelId, String slug, Entry entry) {
        List<Path> cards;
        try {
            cards = noteRenderer.renderEntryCard(slug, entry.date());
        } catch (RuntimeException e) {
            log.warn("카드 렌더 실패(저장은 유지됨, --rerender로 복구 가능): slug={} date={}", slug, entry.date(), e);
            responder.post(channelId, MochaMessages.SAVE_DONE_NO_IMAGE);
            return;
        }
        int delivered = 0;
        for (Path card : cards) {
            try {
                // 캡션은 첫 성공 카드에만 싣는다 — 같은 안내를 카드 수만큼 반복하지 않는다(TΔ5b).
                responder.postImage(channelId, card, delivered == 0 ? MochaMessages.SAVE_DONE_CAPTION : null);
                delivered++;
            } catch (RuntimeException e) {
                log.warn("카드 배달 실패(저장은 유지됨, --rerender로 복구 가능): slug={} card={}",
                        slug, card.getFileName(), e);
            }
        }
        if (delivered == 0) {
            responder.post(channelId, MochaMessages.SAVE_DONE_NO_IMAGE);
        } else if (delivered < cards.size()) {
            responder.post(channelId, MochaMessages.SAVE_DONE_PARTIAL_IMAGE);
        }
    }

    // 버튼 1회 소진 — 커밋·배달 이후 미리보기 버튼을 제거하고 상태 문구로 교체한다.
    // POLICY: 갱신 실패는 저장/취소 결과를 되돌리지 않는다 — 로그만 (ref: plan.md#ADR-20, AC-22). responder도 내부적으로
    //         삼키지만, 여기서 한 번 더 감싸 어떤 예외도 커밋·배달 이후 흐름을 끊지 않게 한다.
    private void finalizePreviewQuietly(String channelId, PendingNote pending, String statusText) {
        try {
            responder.finalizePreview(channelId, pending, statusText);
        } catch (Exception e) {
            log.warn("미리보기 버튼 소진 실패(커밋·배달 결과 유지): channel={}", channelId, e);
        }
    }

    // draft(Note)에서 노트 단위 메타만 뽑는다 — 엔트리·slug·타임스탬프는 upsertEntry가 다룬다.
    private static NoteMeta metaOf(Note draft) {
        return new NoteMeta(
                draft.coffeeName(),
                draft.roastery(),
                draft.beans(),
                draft.roastLevel(),
                draft.officialNotes(),
                draft.sources());
    }

    // draft의 이번 시음 엔트리(마지막 1건)를 교체한 새 draft를 만든다.
    private static Note withLatestEntry(Note draft, Entry entry) {
        List<Entry> entries = new java.util.ArrayList<>(draft.entries());
        entries.set(entries.size() - 1, entry);
        return new Note(
                draft.slug(), draft.coffeeName(), draft.roastery(), draft.beans(),
                draft.roastLevel(), draft.officialNotes(), draft.sources(),
                entries, draft.createdAt(), draft.updatedAt());
    }

    // 이번 시음 엔트리 — draft.entries는 1건 전제(확인 미리보기와 동일 가정). 마지막 엔트리를 취한다.
    private static Entry latestEntry(Note draft) {
        List<Entry> entries = draft.entries();
        if (entries == null || entries.isEmpty()) {
            return null;
        }
        return entries.get(entries.size() - 1);
    }
}
