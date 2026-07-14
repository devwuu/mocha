package com.devwuu.mocha.slack;

import com.devwuu.mocha.domain.PendingNote;
import com.devwuu.mocha.pipeline.AliasGenerator;
import com.devwuu.mocha.pipeline.NoteEnricher;
import com.devwuu.mocha.pipeline.NoteExtractor;
import com.devwuu.mocha.pipeline.NoteMatcher;
import com.devwuu.mocha.pipeline.NoteSearchService;
import com.devwuu.mocha.pipeline.PendingReviser;
import com.devwuu.mocha.pipeline.PhotoInfoExtractor;
import com.devwuu.mocha.render.NoteRenderer;
import com.devwuu.mocha.repository.NoteRepository;
import com.devwuu.mocha.repository.PendingStore;
import com.devwuu.mocha.repository.PhotoBufferStore;
import com.devwuu.mocha.repository.PhotoStore;
import com.devwuu.mocha.repository.SearchSessionStore;
import com.devwuu.mocha.repository.TransitionSlot;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneId;

/**
 * 대화 유스케이스 façade — {@link ConversationRouter}가 정한 분기를 책임 축별 flow에 위임한다
 * (ref: plan.md §1 [2]~[8], ADR-3·ADR-24·ADR-31).
 * <p>의도 게이트는 라우터 층({@link DefaultConversationRouter})에 있다 — 여기는 의도가 확정된 뒤의
 * 진입점이다(ADR-24 구현 배치, changes/0011 TΔ3). 라우터 계약(시그니처)은 이 façade에 남고,
 * 실제 오케스트레이션은 내부 협력자가 소유한다(ADR-31, changes/0013):
 * <ul>
 *   <li>{@link SlackRecordFlow} — 신규 기록 파이프라인({@link #startNewNote})·pending 수정({@link #revisePending})·
 *       [저장]/[취소] 커밋 게이트({@link #confirmSave}/{@link #cancel})·의도 불일치 안내({@code guide*}).</li>
 *   <li>{@link SlackSearchFlow} — 검색 세션 시작·계속·종료({@link #searchNotes}/{@link #endSearch})·후보 목록·카드 조회.</li>
 *   <li>{@link SlackEditFlow} — 수정 세션 진입·edit 커밋·날짜 충돌(SlackRecordFlow·SlackSearchFlow가 mode=edit 분기에서 호출).</li>
 *   <li>{@link SlackPhotoIntake} — 사진 수신({@link #receiveMedia})·버퍼·스테이징·포맷 입구 검증·OCR 오버레이.</li>
 *   <li>{@link FlowMessages} — 사용자 안내 문구 상수.</li>
 * </ul>
 * <p>이름에 Slack을 붙인 이유: Slack 수신 타입·전송에 결합된 유일 구현체라 Default 접두 대신 결합 채널을
 * 명시한다 — 범용 경계 인터페이스 {@link ConversationFlows}와 레벨을 구분한다(내부 flow·{@link SlackPhotoIntake}과
 * 동일 규칙).
 * <p>POLICY: 라우터↔flows 계약과 동작은 분리 전후 동일 — 이 façade는 조립·위임만 갖는다 (ref: plan.md#ADR-31).
 */
@Component
public class SlackConversationFlows implements ConversationFlows {

    // 날짜/타임스탬프는 Asia/Seoul 기준 — NoteRepository·PendingStore와 동일(V-3).
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private final SlackRecordFlow recordFlow;
    private final SlackSearchFlow searchFlow;
    private final SlackPhotoIntake photoIntake;

    @Autowired
    public SlackConversationFlows(
            PendingStore pendingStore,
            NoteRepository noteRepository,
            NoteRenderer noteRenderer,
            SlackResponder responder,
            NoteExtractor noteExtractor,
            NoteMatcher noteMatcher,
            NoteEnricher noteEnricher,
            AliasGenerator aliasGenerator,
            PhotoInfoExtractor photoInfoExtractor,
            PendingReviser pendingReviser,
            PreviewMessenger previewMessenger,
            PhotoDownloader photoDownloader,
            PhotoStore photoStore,
            PhotoBufferStore photoBufferStore,
            SearchSessionStore searchSessionStore,
            NoteSearchService noteSearchService,
            TransitionSlot transitionSlot,
            @Value("${mocha.artifact.dir}") String artifactDir,
            @Value("${mocha.photo.buffer-window}") Duration bufferWindow) {
        this(pendingStore, noteRepository, noteRenderer, responder,
                noteExtractor, noteMatcher, noteEnricher, aliasGenerator, photoInfoExtractor, pendingReviser, previewMessenger,
                photoDownloader, photoStore, photoBufferStore, searchSessionStore, noteSearchService, transitionSlot,
                Path.of(artifactDir), bufferWindow, Clock.system(SEOUL));
    }

    // 테스트에서 시간을 고정하기 위한 생성자(NoteRepository·PendingStore와 동일 패턴).
    // 내부 flow들은 façade가 조립하는 협력자라 Spring 빈이 아니다 — 생성자 계약(시그니처)은 불변으로 두고 여기서 조립한다(ADR-31).
    SlackConversationFlows(
            PendingStore pendingStore,
            NoteRepository noteRepository,
            NoteRenderer noteRenderer,
            SlackResponder responder,
            NoteExtractor noteExtractor,
            NoteMatcher noteMatcher,
            NoteEnricher noteEnricher,
            AliasGenerator aliasGenerator,
            PhotoInfoExtractor photoInfoExtractor,
            PendingReviser pendingReviser,
            PreviewMessenger previewMessenger,
            PhotoDownloader photoDownloader,
            PhotoStore photoStore,
            PhotoBufferStore photoBufferStore,
            SearchSessionStore searchSessionStore,
            NoteSearchService noteSearchService,
            TransitionSlot transitionSlot,
            Path artifactDir,
            Duration bufferWindow,
            Clock clock) {
        this.photoIntake = new SlackPhotoIntake(pendingStore, responder,
                photoDownloader, photoStore, photoBufferStore, photoInfoExtractor, bufferWindow, clock);
        SlackEditFlow editFlow = new SlackEditFlow(pendingStore, searchSessionStore, noteRepository, noteRenderer,
                responder, previewMessenger, photoIntake, clock);
        this.recordFlow = new SlackRecordFlow(pendingStore, noteRepository, noteRenderer, responder,
                noteExtractor, noteMatcher, noteEnricher, aliasGenerator, pendingReviser, previewMessenger, transitionSlot,
                photoIntake, editFlow, clock);
        this.searchFlow = new SlackSearchFlow(searchSessionStore, noteSearchService, noteRepository, noteRenderer,
                responder, transitionSlot, editFlow, artifactDir);
    }

    @Override
    public void startNewNote(IncomingMessage message) {
        recordFlow.startNewNote(message);
    }

    @Override
    public void guidePendingExists(IncomingMessage message) {
        recordFlow.guidePendingExists(message);
    }

    @Override
    public void guideNotARecord(IncomingMessage message) {
        recordFlow.guideNotARecord(message);
    }

    @Override
    public void searchNotes(IncomingMessage message) {
        searchFlow.searchNotes(message);
    }

    @Override
    public void endSearch(IncomingMessage message) {
        searchFlow.endSearch(message);
    }

    @Override
    public void revisePending(IncomingMessage message, PendingNote pending) {
        recordFlow.revisePending(message, pending);
    }

    @Override
    public void confirmSave(IncomingAction action) {
        recordFlow.confirmSave(action);
    }

    @Override
    public void cancel(IncomingAction action) {
        recordFlow.cancel(action);
    }

    @Override
    public void receiveMedia(IncomingMedia media) {
        photoIntake.receive(media);
    }
}
