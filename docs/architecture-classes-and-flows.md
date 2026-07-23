# 모카(Mocha) — 클래스 역할과 기능 흐름

> 이 문서는 `src/main/java/com/devwuu/mocha/` 전체 클래스(91개)의 역할과, 기능별 처리 흐름을 그래프로 정리한 참조 문서다.
> 소스와 spec(`specs/coffee-note-agent/`)을 기준으로 작성했으며, 도메인 특유 용어는 §1 용어 사전에 정의를 두었다. 본문에서 처음 나오는 용어는 **굵게** 표시한다.

---

## 목차

1. [용어 사전](#1-용어-사전)
2. [전체 아키텍처 개관](#2-전체-아키텍처-개관)
3. [패키지별 클래스 역할](#3-패키지별-클래스-역할)
4. [기능별 흐름 그래프](#4-기능별-흐름-그래프)

---

## 1. 용어 사전

모카는 "Slack에 자연어로 던진 커피 감상을 구조화해 로컬 JSON으로 기록하고 카드 이미지·인덱스 HTML로 렌더링하는 1인용 에이전트"다. 아래 용어들은 코드 전반에 등장하며, 각 정의는 코드상 실제 의미 기준이다.

### 데이터 단위

| 용어 | 정의 |
|---|---|
| **노트(Note)** | **커피 1종**에 대한 기록 전체. `data/notes/<slug>.json` 파일 하나에 대응한다. 커피명·로스터리·원산지 같은 "커피의 사실"과, 날짜별 시음 기록(엔트리) 목록을 담는다. |
| **엔트리(Entry)** | 노트에 내장된 **날짜별 시음 기록 1건**(그 커피를 그 날 마신 기록 — 내 감상·평가·레시피). "버전 = 날짜"가 원칙이라 같은 날짜에 다시 기록하면 새 엔트리가 생기는 게 아니라 그날 엔트리가 덮어써진다. |
| **slug** | 노트의 파일명이자 식별자. `YYYY-MM-DD-HHmmss`(최초 기록일+생성 시각) 형식의 대체키다. 커피를 식별하는 값이 아니라 파일 PK — 커피의 동일성 판단은 커피명+로스터리(+별칭)가 한다. |
| **official_notes(공식 노트)** | 로스터리가 상품 페이지·원두 봉투에 **전시한 테이스팅 노트**("자스민, 베르가못" 등). 사용자의 감상(`my_taste`)과 구분되는 "로스터리가 말하길" 영역이며, 로스터리 출처가 없으면 비워둔다(일반 출처 대체 금지). |
| **my_taste / my_taste_original** | 사용자가 실제로 느낀 감상. `my_taste`는 한국어 음슴체로 정규화한 표시용 값("맛있더라"→"맛있었음"), `my_taste_original`은 말한 그대로의 원문 보존용 값. 항상 함께 저장되고 렌더는 정규화본만 쓴다. |
| **레시피(Recipe)** | 추출(브루잉) 정보 3항목 — 원두량(g)·물량(ml)·분쇄도. 사용자 발화에서만 채우고(검색·사진 보강 금지) 언급 없는 항목은 비운다. |
| **평가(Rating)** | 4단계 범주형 단일 선택 — `완전 내스타일`/`맛있다`/`맛은 있는데 내스타일은 아님`/`맛이 없다`. 숫자 별점 없음. |
| **별칭(Aliases)** | 노트의 **내부 매칭·검색 전용** 한국어 음차·이표기 목록(예: "Ethiopia Chelbesa" → "에티오피아 첼베사"). 표기가 달라도 같은 커피로 매칭하기 위한 장치로, 카드·인덱스·미리보기 어디에도 표시하지 않는다. 신규 노트 첫 저장 시 LLM 1콜로 생성하고, 이후 같은 노트로 매칭된 기록의 관측 표기를 콜 없이 축적한다. |
| **출처(Source) / Sourced** | 필드 값이 어디서 왔는지 — `user`(사용자 발화) / `photo`(사진 OCR) / `search`(웹 검색). `Sourced<T>`는 값+출처를 함께 담는 래퍼다. 우선순위는 `user > photo > search`로, 하위 출처가 상위 출처 값을 덮지 못한다. 미리보기에서 `(검색)`/`(사진)` 표기의 근거. |

### 확인 플로우(저장 전 대기)

| 용어 | 정의 |
|---|---|
| **pending(확인 대기)** | 에이전트가 만든 기록·수정 **제안이 사용자 [저장] 확인을 기다리는 상태**. `data/pending.json`에 영속화되어 재시작에도 생존한다. 사용자당 최대 1건(단일 대기 원칙) — 대기 중 다른 커피의 새 제안은 거부된다. TTL 초과 시 무효. |
| **draft** | pending 안의 **작성 중인 노트 사본**(아직 커밋 안 된 초안). 수정 발화가 오면 이 위에 누적 갱신된다. |
| **record 모드 / edit 모드** | pending의 두 종류. `record` = 신규 기록의 확인 대기, `edit` = 이미 저장된 노트를 고치는 수정 세션(원본 참조 `target`과 날짜 충돌 플래그를 추가로 가짐). |
| **미리보기(preview)** | pending 내용을 Slack Block Kit 메시지로 보여주는 확인 화면([저장]/[취소] 버튼 포함). `preview_ts`는 그 메시지의 타임스탬프 — 내용이 바뀌면 재전송이 아니라 같은 메시지를 edit로 갱신한다. |
| **커밋(commit)** | 사용자가 [저장] 버튼을 눌러 draft를 실제 노트 JSON 파일에 영속화하는 것. 이 확인 없이는 어떤 경로로도 노트가 쓰이지 않는다(자연어로는 커밋 불가 — 버튼 전용). |
| **버튼 소진(finalize)** | [저장]/[취소] 처리 완료 후 미리보기 메시지에서 버튼을 제거하고 "저장 완료"/"취소됨" 상태 문구로 교체하는 것. 버튼은 1회용이라 재클릭이 불가능해진다. |
| **매칭(match / MatchInfo)** | 이번 기록이 **새 노트**인지 **기존 노트의 새 시음**인지의 판정 결과. `new` 또는 `existing`(+대상 slug·날짜)으로 미리보기에 표시된다. |

### 에이전트 루프

| 용어 | 정의 |
|---|---|
| **에이전트 턴(agent turn)** | 사용자 발화 1개에 대한 처리 전체 — 컨텍스트 조립 → LLM 호출 ↔ tool 실행 루프 → 최종 텍스트 응답. 버튼 액션을 제외한 모든 수신이 에이전트 턴으로 처리된다. |
| **tool (function tool)** | 에이전트 루프에서 모델이 호출하는 실행 단위. 5종 — `list_notes`(노트 메타 목록)·`get_note`(노트 전체)·`propose_record`(신규 기록 제안)·`propose_edit`(수정 제안)·`send_entry_card`(기존 카드 재전송) — 에 OpenAI 내장 `web_search`가 더해진다. **데이터를 바꾸는 경로는 제안 tool 2종뿐이고, 그 효과도 pending 생성까지다.** |
| **제안(proposal)** | 데이터를 바꾸려는 tool 호출(`propose_record`/`propose_edit`). 서버 검증을 통과하면 pending + 미리보기가 만들어지고, 실제 저장은 [저장] 버튼이 한다. |
| **트랜스크립트(transcript)** | 에이전트 턴 **사이**의 대화 문맥(작업 트랜스크립트). "그거"류 지시어, 되물음 왕복, 잡담→기록 전환을 해석하는 근거가 되는 (사용자 발화, 모카 응답) 쌍의 목록이다. 사용자당 1건, **메모리 전용**(재시작 시 소멸 — pending과 달리 파일로 남기지 않음), TTL·턴 수 상한을 가진다. |
| **턴(turn)** | 두 의미로 쓰인다. ① 트랜스크립트에 저장되는 대화 1왕복(`TranscriptTurn` = 사용자 발화 + 모카 응답 쌍), ② 위의 "에이전트 턴"(실행 1회). |
| **접힘(fold)** | 트랜스크립트를 비우는 결정론 이벤트. 제안 성공·[저장]·[취소] 시점에 접는다 — 이후 문맥은 대화 이력 대신 구조화된 pending draft가 대신하기 때문. LLM 요약 콜 없이 그냥 비운다. |
| **환각 필터(hallucination filter)** | 모델이 지어낸 실존하지 않는 slug·엔트리를 대상으로 제안이 진행되지 않게 막는 서버 검사. 미존재 대상은 오류 사유를 tool 결과로 돌려줘 에이전트가 루프 안에서 정정한다. |
| **strict schema** | 제안 tool 인자의 JSON 스키마 강제(전 필드 required, additionalProperties=false). 인자의 **형태**는 스키마가, **값 수준 규칙**(rating 4범주 등)은 서버 검증(`ProposalValidator`)이 담당한다. |
| **폴백(fallback)** | 에이전트 턴 실패 시(LLM 오류·tool 상한 도달) 수렴하는 결정론 경로 — pending·노트를 건드리지 않고 "다시 보내달라" 안내만 하며, 사용자 원문은 파일 로그에 남아 유실되지 않는다. |

### 사진 처리

| 용어 | 정의 |
|---|---|
| **스테이징(staging)** | 수신 사진을 노트 소속이 확정되기 전 `data/photos/.staging/<userId>/`에 임시 보관하는 것. [저장] 커밋 시 아카이브로 이동하고, [취소]·만료 시 폐기된다. |
| **아카이브(archive)** | 저장 확정된 사진의 최종 위치 `data/photos/<slug>/<date>/`. 사진의 역할은 OCR 입력과 기록 보관뿐 — 카드·인덱스에 렌더링하지 않고 JSON에도 경로를 기록하지 않는다(폴더 구조가 노트·날짜와의 유일한 연결). |
| **버퍼(photo buffer)** | 텍스트보다 먼저 도착한 사진을 시간 윈도우(기본 10분) 동안 묶어두는 장치(`data/photo-buffer.json`). 뒤이어 온 텍스트가 윈도우 안이면 사진이 그 기록으로 흡수된다. |
| **스윕(sweep)** | 앱 시작 시 pending·버퍼 어디에도 속하지 않는 **고아** 스테이징 파일을 청소하는 것. 살아있는 대기는 건드리지 않는다. |
| **OCR / vision 추출(VisionExtraction)** | 수신 사진에서 커피 정보(커피명·로스터리·원산지·가공·로스팅·공식 노트)를 vision 모델로 읽어 구조화하는 것. 에이전트 tool이 아니라 **루프에 들어가기 전 결정론 전처리 1콜**이며, 결과는 에이전트 컨텍스트에 주입된다. 추측 금지(못 읽은 필드는 null), 실패해도 흐름은 계속된다. |
| **매직바이트 판별** | 사진 포맷을 확장자·MIME이 아닌 파일 선두 바이트로 판별하는 것. vision 지원 포맷(JPEG/PNG/GIF/WebP)만 스테이징을 통과하고, HEIC는 Slack 썸네일로 대체, 그 외는 버리고 안내한다. |

### 렌더링

| 용어 | 정의 |
|---|---|
| **엔트리 카드(entry card)** | 시음 엔트리 1건(커피 × 날짜)을 담은 인스타그램 4:5 비율(1080×1350) 공유용 JPG. `artifact/cards/<slug>/<date>.jpg`. Thymeleaf로 조판한 HTML을 헤드리스 Chromium으로 래스터화해 굽는다(카드 HTML 자체는 파일로 남기지 않는 중간 입력). |
| **인덱스(index)** | 모든 시음 엔트리를 최신순 행 목록으로 나열한 `artifact/index.html`. 각 행이 해당 엔트리 카드 JPG로 링크하며, 웹서버 없이 `file://`로 연다. |
| **파생물(artifact)** | `artifact/` 아래의 카드 JPG·인덱스 HTML 등 — JSON만으로 언제든 전체 재생성 가능하므로 지워도 데이터 손실이 없다. |
| **리렌더(rerender)** | JSON 원본에서 파생물 전체를 재생성하는 것. `--rerender` CLI 인자로 Slack 연결 없이 단독 실행된다. 반대로 **증분 렌더**는 저장 직후 방금 그 엔트리 카드 1장만 굽는 것. |
| **고아 카드 정리(prune)** | 전체 리렌더 시 JSON 기준으로 더 이상 존재하지 않는 카드 파일(날짜 이동·삭제의 잔여물)을 삭제해 파생물을 JSON과 일치시키는 것. |
| **테마(Theme)** | 카드·인덱스의 디자인 세트(type-a 세리프 / type-b 고딕). 템플릿 폴더와 번들 폰트를 선택하며 데이터에는 영향이 없다. |

---

## 2. 전체 아키텍처 개관

**기록 1건의 일생**을 따라가면 전체 구조가 그대로 드러난다. Slack에서 오는 수신은 세 종류뿐이고(① 사진 → ② 텍스트 → ③ 버튼), 시간 순서대로 각 단계에 합류한다:

- **① 사진**(선택, 텍스트보다 먼저 올 수 있음)은 스테이징·버퍼에 대기하다가 ②에 흡수되고,
- **② 텍스트**는 에이전트 턴을 돌아 **pending(확인 대기)까지만** 쓰고 미리보기를 띄우며,
- **③ [저장] 버튼**만이 노트 JSON을 실제로 쓰고, 그 원본에서 카드·인덱스가 파생된다.

OpenAI SDK·Slack SDK 타입은 각각 어댑터 구현 클래스 안에만 존재하고, 나머지 코드는 인터페이스 경계만 참조한다(교체 가능성 NFR-4). OpenAI 콜은 정확히 세 지점(점선)에서만 일어난다.

```mermaid
flowchart TB
    SLACK(["Slack Socket Mode"])
    OPENAI(["OpenAI Responses API"])
    GW["SlackGateway<br/>이벤트 파싱 · 즉시 ack"]
    ROUTER{"AgentConversationRouter<br/>수신 종류 분기"}
    INTAKE["SlackPhotoIntake<br/>다운로드 · 매직바이트 검증"]
    STAGING[("data/photos/.staging/<br/>+ photo-buffer.json")]

    subgraph TURN["에이전트 턴 — agent · llm (쓰기 효과는 pending까지)"]
        OCR["PhotoInfoExtractor<br/>버퍼 사진 OCR 1콜 — 있을 때만, 루프 밖"]
        ASM["AgentContextAssembler<br/>프롬프트 + 트랜스크립트 + pending + OCR 조립"]
        LOOP["OpenAiAgentClient ↔ AgentToolkit<br/>모델↔tool 루프 — 노트 읽기 · web_search · 제안 검증"]
    end

    TEXTONLY["텍스트 응답만<br/>데이터 무변화"]
    PENDING[("data/pending.json<br/>확인 대기 — 사용자당 1건")]
    PREVIEW["PreviewMessenger<br/>미리보기 + 저장/취소 버튼"]
    BTN{"③ 버튼 클릭<br/>(Slack → 게이트웨이 재경유)"}
    CANCEL["pending · 스테이징 폐기<br/>노트 무변화"]

    subgraph COMMITG["결정론 커밋 — slack (노트가 쓰이는 유일한 경로)"]
        COMMIT["SlackCommitHandler<br/>pending 검증 → 커밋"]
    end

    ARCHIVE[("data/photos/slug/date/<br/>사진 아카이브 확정")]
    NOTES[("data/notes/*.json<br/>원본 — source of truth")]

    subgraph REN["파생물 — render"]
        RENDER["ThymeleafNoteRenderer + Playwright<br/>카드 증분 렌더 · 인덱스 갱신"]
        CARDS[("artifact/<br/>cards/*.jpg · index.html")]
    end

    DONE["SlackResponder<br/>카드 업로드 · 버튼 소진"]

    SLACK --> GW --> ROUTER
    ROUTER -->|"① 사진"| INTAKE --> STAGING
    ROUTER -->|"② 텍스트"| OCR
    STAGING -.->|"윈도우 내 텍스트 도착 시 흡수"| OCR
    OCR --> ASM --> LOOP
    LOOP -->|"잡담 · 되묻기"| TEXTONLY
    LOOP -->|"제안 검증 통과"| PENDING --> PREVIEW --> BTN
    BTN -->|"[취소]"| CANCEL
    BTN -->|"[저장]"| COMMIT
    COMMIT --> ARCHIVE
    COMMIT --> NOTES --> RENDER --> CARDS --> DONE

    LOOP <-.->|"루프 콜"| OPENAI
    OCR -.->|"vision 1콜"| OPENAI
    COMMIT -.->|"신규 노트 별칭 1콜"| OPENAI
```

그림에 없는 진입로는 하나뿐이다: `--rerender` CLI(§4.6)는 Slack을 켜지 않고 `data/notes/*.json` → `artifact/` 전체 재생성만 수행한다 — 파생물은 원본에서 언제든 다시 만들 수 있다는 불변식의 실행 형태다.

핵심 불변식:

- **JSON이 유일한 원본** — HTML·JPG는 언제든 재생성 가능한 파생물이다.
- **쓰기 경로는 두 단계로 격리** — 에이전트는 제안(pending)까지만, 노트 커밋은 [저장] 버튼만 한다.
- **모든 파일 쓰기는 임시파일 → 원자적 move**.
- 트랜스크립트만 의도적으로 메모리 전용(재시작 시 소멸), pending·버퍼·스테이징은 파일로 생존.

### 2.1 대화 문맥 모델 — 무상태 API 위에서 연속성 만들기

에이전트 관련 클래스(`ConversationTranscript`·`AgentContextAssembler` 등)가 왜 이런 모양인지는 하나의 전제에서 전부 따라 나온다:

> **LLM API는 무상태(stateless)다.** 모델은 이번 요청에 실려 온 것만 보고 응답하며, 요청이 끝나면 서버는 그 대화를 기억하지 않는다. "모카가 이전 대화를 기억한다"는 감각은 전부 **클라이언트(모카)가 매 턴 이전 문맥을 다시 조립해 실어 보내서** 만들어진다.

이 전제 위에서 구성요소별 존재 이유:

1. **컨텍스트 = 이번 턴에 모델이 보는 것 전부.** 매 턴 `AgentContextAssembler`가 처음부터 다시 조립한다 — 시스템 프롬프트(항구 정책) + 턴 컨텍스트(today·pending draft·OCR이라는 동적 사실) + 트랜스크립트(이전 대화 이력을 messages로 재구성) + 이번 발화. 어느 재료도 서버에 남아 있지 않으므로, 매 턴 전송분이 곧 모델의 기억 전체다.

2. **트랜스크립트 = 턴 사이 연속성의 유일한 근거.** API가 기억하지 않으므로 "그거"류 지시어·되물음 왕복은 클라이언트가 이력을 보관했다가 재전송해야만 해석된다. `ConversationTranscript`가 그 보관소다. 단, 전체 로그가 아니라 (사용자 발화, 모카 최종 응답) 쌍만 남긴다 — 턴 내부의 tool 호출·중간 과정은 다음 턴 해석에 불필요하므로 처음부터 싣지 않는 압축 저장이다.

3. **예외 — 한 턴 안의 tool 루프는 서버가 이어준다.** OpenAI Responses API의 `previous_response_id`로, `OpenAiAgentClient`는 루프 이터레이션마다 이전 내용 전체를 재전송하는 대신 응답 id + function_call_output만 싣는다. 이 서버측 연속성의 수명은 턴 하나다 — `runTurn`이 끝나면 버려지고, 턴과 턴 사이는 완전한 무상태로 돌아간다.

4. **문맥은 무한히 커질 수 없으므로 접는다 — 단, 결정론적으로.** 범용 에이전트(예: Claude Code)가 컨텍스트가 길어지면 LLM 요약으로 압축(compaction)하는 것과 같은 문제를, 모카는 LLM 판단 없이 관측 가능한 이벤트만으로 푼다: 제안 성공·[저장]/[취소] 시 접힘(fold), 턴 상한 초과 시 오래된 턴 드롭, TTL 경과 시 소멸. 접힘이 가능한 이유는 **구조화된 pending draft가 이후 문맥의 압축본 역할을 대신하기** 때문이다 — 요약 콜 없이도 "지금까지 합의된 내용"이 draft로 남는다. (요약 비도입은 의도된 right-sizing — 문맥 절단 불편이 실제 관측되면 재론, plan.md §6.)

재료별 보관 주체와 수명으로 정리하면:

| 컨텍스트 재료 | 보관 주체 | 수명 |
|---|---|---|
| 시스템 프롬프트 | `AgentSystemPrompt` (코드 상수) | 영구 |
| today·타임존 | `Clock` (조립 시점 평가) | 턴 1회 |
| pending draft | `data/pending.json` (파일) | [저장]/[취소] 커밋 또는 TTL |
| OCR 결과 | 턴 내 전처리 산물 (미보관) | 턴 1회 |
| 대화 이력 | `ConversationTranscript` (메모리) | 접힘·턴 상한·TTL·재시작 |
| 이번 발화 | Slack 수신 | 턴 1회 |
| 턴 내 tool 루프 상태 | OpenAI 서버 (`previous_response_id`) | 턴 1회 |

---

## 3. 패키지별 클래스 역할

### 3.1 `slack` — 수신 진입·분기·커밋 (5개)

| 클래스 | 종류 | 역할 |
|---|---|---|
| `SlackGateway` | class | Slack Socket Mode 수신 진입점. Bolt 이벤트를 내부 값객체(`Incoming*`)로 파싱해 라우터에 넘기는 얇은 계층으로, 즉시 ack + 백그라운드 처리로 재전송 루프를 막고 봇 자신의 메시지를 걸러 에코 루프를 차단한다. Slack SDK 타입이 이 클래스 밖으로 새지 않는다. `--rerender` 프로파일에서는 비활성. |
| `ConversationRouter` | interface | 수신 이벤트(메시지/버튼/사진)를 받는 라우팅 경계. 게이트웨이(파싱)와 분기 로직을 분리한다. |
| `AgentConversationRouter` | class | 메인 라우터. 텍스트는 [사진 버퍼 흡수 → OCR 전처리 → 컨텍스트 조립 → 에이전트 턴 → 응답]으로, 버튼은 `SlackCommitHandler`로, 사진은 `SlackPhotoIntake`로 보낸다. 턴 실패 시 pending·노트 무변화 + 폴백 안내로 수렴시키는 지점이기도 하다. |
| `SlackCommitHandler` | class | **[저장]/[취소] 버튼의 커밋 전담.** 저장 시: pending 검증(TTL 등) → 스테이징 사진 아카이브 이동 → (신규 노트면) 별칭 생성 1콜 → 노트 JSON 커밋 → 카드 증분 렌더 + Slack 업로드 → 버튼 소진. 취소 시: pending·스테이징 폐기 + 안내. 사용자 확인 없이는 절대 노트를 쓰지 않는다는 정책의 구현 지점. |
| `StagingSweeper` | class | 앱 시작 시 1회 실행되는 스윕 훅 — pending·버퍼 어디에도 속하지 않는 고아 스테이징 사진만 청소한다. |

### 3.2 `slack.inbound` — 수신 값객체·사진 유입 (8개)

| 클래스 | 종류 | 역할 |
|---|---|---|
| `IncomingMessage` | record | 평문 메시지의 내부 표현 (userId·channelId·text·ts). |
| `IncomingAction` | record | 버튼 액션의 내부 표현. `actionId`로 저장/취소를 구분하고 `messageTs`가 버튼 소진 대상 미리보기를 가리킨다. |
| `IncomingMedia` | record | 사진 묶음 수신의 내부 표현. `ts`가 버퍼 그룹핑 기준 시각. |
| `IncomingPhoto` | record | 사진 1건 (url·파일명·MIME·썸네일 후보 목록). HEIC 대체용 썸네일 URL을 최대 해상도 우선으로 싣는다. |
| `PhotoDownloader` | interface | "URL → 바이트" 사진 다운로드 경계(HTTP·토큰 세부 은닉). |
| `SlackPhotoDownloader` | class | 구현체 — Slack `url_private`를 봇 토큰 Bearer 인증으로 GET. 실패는 `PhotoDownloadException`으로 수렴. |
| `PhotoDownloadException` | exception | 사진 다운로드 실패 신호. 상위에서 "안내 + pending 미생성" 실패 모드로 처리된다. |
| `SlackPhotoIntake` | class | **사진 유입 경로 전담.** 다운로드 → 매직바이트 포맷 검증(HEIC는 썸네일 대체, 미지원은 버리고 안내) → 스테이징 → 버퍼 그룹핑. 텍스트 턴에서 버퍼를 흡수해 OCR(`readPhotoInfo`)을 트리거하고, 커밋 시 스테이징→아카이브 이동·날짜 이동 동반 이동도 수행한다. |

### 3.3 `slack.outbound` — 응답·미리보기 송신 (5개)

| 클래스 | 종류 | 역할 |
|---|---|---|
| `SlackResponder` | interface | 결과 통지 송신 경계 — 안내 텍스트(`post`), 카드 JPG 업로드(`postImage`), 버튼 소진(`finalizePreview`). 에이전트 tool 계층도 응답 창구로 재사용한다. |
| `SlackApiResponder` | class | 구현체 — Slack MethodsClient로 chatPostMessage / filesUploadV2 / chatUpdate 실행. 카드 업로드 실패만 예외로 던져 호출부가 텍스트 폴백하게 한다. |
| `PreviewMessenger` | class | 미리보기 전송/갱신 어댑터 — `preview_ts` 유무에 따라 신규 전송 또는 같은 메시지 edit. 제안 tool이 미리보기를 보내는 창구. |
| `PreviewBlocks` | class | pending → 미리보기 Block Kit 변환 순수 함수. 출처 태그(`(검색)`/`(사진)`), edit 모드 ✏️ 헤더, 날짜 충돌 경고, [저장]/[취소] 버튼, 버튼 소진 후 블록까지 조립한다. |
| `MochaMessages` | final class | 모카(강아지 "~멍" 톤) 사용자 안내 문구 상수 모음(저장 완료·취소·폴백·포맷 미지원 등). |

### 3.4 `agent` — 루프 드라이버 (3개)

| 클래스 | 종류 | 역할 |
|---|---|---|
| `AgentClient` | interface | 에이전트 루프 드라이버의 경계. "모델↔tool 루프를 상한까지 돌리고 최종 텍스트를 반환한다"는 계약만 정의 — 루프 밖 코드가 OpenAI SDK를 모르게 한다. |
| `OpenAiAgentClient` | class | OpenAI Responses API 기반 구현체. 모델 호출 → function call 수집 → tool 실행 → 결과를 다음 입력에 실어 재호출을 반복하고, tool 호출이 없는 응답이 오면 그 텍스트로 턴을 마친다. tool 호출 상한 검사, 미등록 tool·실행 오류를 `{"error": 사유}` tool 결과로 돌려주는 정정 루프, 내장 web_search 관측 로그를 담당한다. |
| `AgentException` | exception | 턴 실패 신호(모델 오류·상한 도달). 라우터가 이를 받아 결정론 폴백으로 수렴시킨다. |

### 3.5 `agent.conversation` — 대화 문맥 (2개)

| 클래스 | 종류 | 역할 |
|---|---|---|
| `ConversationTranscript` | class | 작업 트랜스크립트 보관소 — 사용자당 1건, 메모리 전용. 턴 추가(`append`, 상한 초과 시 오래된 턴 드롭), 조회(`view`, TTL 경과 시 소멸), 접힘(`clear` — 제안 성공/[저장]/[취소] 트리거)을 제공한다. |
| `TranscriptTurn` | record | 트랜스크립트의 턴 1건 = (사용자 발화, 모카 응답) 쌍. |

### 3.6 `agent.prompt` — 턴 입력 조립 (4개)

| 클래스 | 종류 | 역할 |
|---|---|---|
| `AgentSystemPrompt` | final class | 시스템 프롬프트의 단일 소유 지점 — 모카 페르소나, 대화 경계(커피 무관 발화엔 tool 금지), 언어 정책(고유명사 원문 유지·my_taste 음슴체), 출처 우선순위, 웹 검색 보강 규칙을 텍스트로 인코딩. |
| `AgentContextAssembler` | class | 턴 컨텍스트 조립기 — 트랜스크립트 + pending draft 요약 + OCR 결과 + 오늘 날짜(Asia/Seoul)를 시스템 프롬프트에 덧붙이고, 대화 이력을 메시지 목록으로 재구성해 `AgentTurnInput`을 만든다. |
| `AgentTurnInput` | record | 턴 1회의 입력 (instructions + 메시지 목록). SDK 무관 경계 타입. |
| `AgentInputMessage` | record | 턴 입력 메시지 1건 (Role: USER/MOCHA + 내용). 드라이버가 SDK 메시지로 변환한다. |

### 3.7 `agent.tool` — function tool 정의·실행·검증 (15개)

| 클래스 | 종류 | 역할 |
|---|---|---|
| `AgentTool` | record | tool 1종의 정의+실행기 (이름·설명·인자 스키마·`Executor`). Executor는 "인자 JSON → 결과 JSON" 함수형 인터페이스. |
| `AgentToolkit` | class | tool 5종의 façade — 읽기 tool(`NoteLookupTools`)과 쓰기 tool(`ProposalTools`)을 조립하고, 턴마다 userId(pending 소유자)·channelId(배달처)를 바인딩한 tool 목록을 공급한다. |
| `NoteLookupTools` | class | **읽기 tool 3종**: `list_notes`(전체 노트 메타+별칭 — 매칭·검색의 출발점), `get_note`(노트 전체, 미존재 slug는 오류 = 환각 필터), `send_entry_card`(기존 카드 JPG 재전송, 파일 부재 시에만 증분 렌더). 노트·pending 파일을 절대 바꾸지 않는다. |
| `ProposalTools` | class | **쓰기 제안 tool 2종**: `propose_record`(신규 기록 제안)·`propose_edit`(저장 노트 수정 제안). 인자 파싱 → 서버 검증 → pending 생성/갱신 → 미리보기 전송 → 트랜스크립트 접힘까지가 효과의 전부(커밋은 버튼만). strict schema 문자열도 여기서 정의한다. |
| `ProposalValidator` | class | 제안 인자의 **서버 값검증** — rating 4범주, source enum 제약(커피명은 user/photo만), 레시피 정규화, 다중 날짜 게이트(V-16), my_taste 병존, 단일 대기 판정. 이동 충돌(V-10) 계산은 제안 수용 지점(`ProposalTools`)의 몫. 위반은 예외가 아니라 사유 있는 거부로 수렴해 에이전트가 루프 안에서 정정하게 한다. |
| `ToolValidation<T>` | sealed interface | 검증 결과 타입 — `Ok(값)` 또는 `Rejected(사유)`. |
| `ToolSupport` | final class | tool 공용 유틸 — 오류 결과 형태(`{"error":...}`) 통일, slug 리졸브. |
| `GetNoteArgs` / `SendEntryCardArgs` | record | 읽기 tool 인자 값객체. |
| `ProposeRecordArgs` / `ProposeEditArgs` | record | 제안 tool 인자의 미검증 원시형. `ProposeEditArgs.Patch`에는 커피명 필드 자체가 없어 이름 변경이 구조적으로 불가능하다. |
| `SourcedArg<T>` | record | 출처 표시 필드의 미검증 원시형(source가 String) — 검증 후 도메인 `Sourced`(enum)로 승격된다. |
| `NoteSummary` | record | `list_notes` 응답 항목(slug·커피명·로스터리·별칭·원산지·공식 노트·최근 시음일). |
| `RecordProposal` / `EditProposal` | record | 검증 통과 후 정규화된 도메인 제안 — pending draft 조립·갱신의 입력. |

### 3.8 `llm` — 루프 밖 보조 LLM 콜 (7개)

| 클래스 | 종류 | 역할 |
|---|---|---|
| `AliasGenerator` | interface | 별칭 생성 경계 — 커피명·로스터리를 받아 한국어 음차·이표기를 반환. 신규 노트 첫 저장 시 노트당 평생 1회만 호출되며, 실패해도 빈 별칭으로 수렴(저장은 유지). |
| `OpenAiAliasGenerator` | class | 구현체 — 최경량 텍스트 모델에 structured output(strict schema)으로 별칭 배열을 받는다. |
| `VisionClient` | interface | 이미지 → 커피 정보 구조화(OCR)의 경계. |
| `OpenAiVisionClient` | class | 구현체 — vision 모델에 이미지(`detail=HIGH`)와 strict schema를 보내 6필드(커피명·로스터리·원산지·가공·로스팅·공식 노트)를 받는다. 추측 금지(미확인 null), 모든 실패는 빈 결과로 수렴. |
| `PhotoInfoExtractor` | class | OCR 전처리 오케스트레이터 — 스테이징 사진들을 **1회 호출**로 읽는다. 장수 상한 초과분은 제외하고, 로컬 바이트를 `data:` URI로 인코딩해 넘긴다(Slack URL은 인증이 필요해 OpenAI가 직접 못 읽음). MIME은 매직바이트로 판별. |
| `VisionExtraction` | record | OCR 결과 값객체(6필드, 미확인은 null). `empty()`가 실패·무정보의 표준 수렴값. |
| `VisionHint` | record | OCR 문맥 힌트(이미 아는 커피명·로스터리) — 오독을 줄이는 용도. 사진만 온 흐름에서는 둘 다 null. |

### 3.9 `render` — JSON → 카드·인덱스 (10개)

| 클래스 | 종류 | 역할 |
|---|---|---|
| `NoteRenderer` | interface | 렌더 경계 — 전체 리렌더(`renderAll`), 엔트리 카드 증분 렌더(`renderEntryCard`), 카드 삭제(`removeEntryCard`, 날짜 이동 시). |
| `ThymeleafNoteRenderer` | class | 주 구현체 — 노트 JSON 전체를 읽어 인덱스 HTML 작성, 엔트리마다 카드 HTML을 Thymeleaf로 조판해 JPG로 굽고, 고아 카드를 정리한다. 폰트·마스코트 자산 복사도 담당. |
| `CardImageRenderer` | interface | "카드 HTML → JPG 래스터화" 경계. |
| `PlaywrightCardImageRenderer` | class | 구현체 — Playwright 헤드리스 Chromium으로 1080×1350 뷰포트 스크린샷을 JPG로 저장(순수 Java로는 flexbox·이모지·웹폰트 렌더가 불가능해 실제 브라우저 엔진 사용). 오프라인 컨텍스트로 CDN 미의존을 강제. |
| `NoteView` | final class | 템플릿용 뷰 모델 컨테이너 — `Index`(인덱스 페이지), `Row`(엔트리 1행), `EntryCard`(카드 1장), `EntryView`(카드 속 엔트리) 중첩 record. |
| `KoreanDates` | final class | 템플릿 헬퍼 — 한국어 날짜 포맷("2026년 7월 10일" 등). |
| `RatingStyle` | final class | 템플릿 헬퍼 — 평가 4범주의 이모지·배지 색상. |
| `RecipeAmounts` | final class | 템플릿 헬퍼 — 레시피 수량 표기(15.0→"15"). |
| `Theme` | enum | 렌더 테마(TYPE_A 세리프 / TYPE_B 고딕) — 템플릿 폴더와 번들 폰트 선택. |
| `RerenderRunner` | class | `--rerender` CLI 진입점 — 전체 리렌더 후 종료. 이 프로파일에서는 Slack 게이트웨이가 비활성이라 상주 인스턴스와 무관하게 안전하다. |

### 3.10 `domain` — 도메인 모델 (11개)

| 클래스 | 종류 | 역할 |
|---|---|---|
| `Note` | record | 커피 1종의 최상위 애그리게이트 — slug, 출처 표시 필드 5종, 공식 노트, 별칭, 검색 출처 링크, 엔트리 목록, 타임스탬프. |
| `Entry` | record | 날짜별 시음 기록 1건 — 날짜(entries 내 유일 키)·my_taste(+원문)·평가·레시피. 원문 누락 시 정규화본을 양쪽에 복사하는 불변식 내장. |
| `Sourced<T>` | record | 값+출처 래퍼. |
| `Source` | enum | 출처 3종(user/photo/search). 정의 외 값은 역직렬화 거부. |
| `Rating` | enum | 평가 4범주. 한국어 라벨로 직렬화. |
| `Recipe` | record | 레시피 3항목 + 정규화 로직(위반 항목만 null 드롭, 전부 없으면 Recipe 자체가 null). |
| `Aliases` | record | 별칭 목록 + 축적 로직 — 관측 표기 병합(`accumulate`), 정규화(소문자화·공백 제거) 기준 중복 제거. 정규화는 대조 기준일 뿐 저장값은 첫 등장 표기를 보존. |
| `NoteMeta` | record | 노트에서 slug·엔트리·타임스탬프를 뺀 "커피의 사실" 묶음 — 신규 노트 생성 입력. |
| `MatchInfo` | record | 매칭 판정 결과(new / existing+slug+date). |
| `PendingNote` | record | 확인 대기 상태 — 모드(record/edit)·draft·수정 대상(target)·날짜 충돌 플래그·매칭·preview_ts·생성 시각. 필드 하나만 바꾼 사본을 만드는 `withX` 메서드 제공. |
| `PhotoBuffer` | record | 사진 버퍼 상태 — 마지막 수신 시각(윈도우 판정 기준)과 스테이징 파일명 목록. |

### 3.11 `repository` — 파일 저장소 (9개)

모든 구현체 공통: 쓰기는 임시 `.tmp` 파일 → 원자적 move, 시간대는 Asia/Seoul, 직렬화는 `MochaObjectMapper`.

| 클래스 | 종류 | 역할 |
|---|---|---|
| `NoteRepository` | interface | 노트 저장소 경계 — 전체/단건 조회, 충돌 없는 slug 발급, 엔트리 병합 저장(`upsertEntry`), 수정 커밋(`applyEdit`). |
| `JsonFileNoteRepository` | class | 구현체 — `data/notes/<slug>.json` 읽기/쓰기. 같은 날짜는 갱신·다른 날짜는 추가(하루 2엔트리 금지), 신규 노트에는 별칭 심기·기존 노트에는 관측 표기 축적, 수정 커밋 시 커피명 불변 이중 방어와 날짜 이동 덮어쓰기 처리. |
| `PendingStore` | interface | pending 저장소 경계 — put/get/clear. |
| `JsonFilePendingStore` | class | 구현체 — `data/pending.json` 단일 파일. TTL 초과분은 get에서 빈 값으로 수렴(만료 pending은 유효 대기가 아님). |
| `PhotoBufferStore` | interface | 사진 버퍼 저장소 경계 — put/get/clear. |
| `JsonFilePhotoBufferStore` | class | 구현체 — `data/photo-buffer.json`. 윈도우 판정은 저장소가 아니라 수신 경로가 한다. |
| `PhotoStore` | interface | 사진 파일 저장소 경계 — 스테이징(`stage`/`readStaged`/`discard`), 아카이브 확정(`commit`), 날짜 이동(`moveEntryPhotos`), 고아 청소용 사용자 목록(`stagedUserIds`). |
| `LocalPhotoStore` | class | 구현체 — `data/photos/.staging/<userId>/`(임시)와 `data/photos/<slug>/<date>/`(확정) 레이아웃 관리. 파일명 안전화·충돌 시 `-N` 유일화. |
| `StagedImage` | record | 스테이징 사진 1장(파일명+바이트) — OCR의 입력 단위. |

### 3.12 `config` · 기타 (8개)

| 클래스 | 종류 | 역할 |
|---|---|---|
| `RepositoryConfig` | @Configuration | 저장소 4종 빈 조립. 경로는 전부 `mocha.data.dir`에서만. |
| `LlmConfig` | @Configuration | OpenAI 클라이언트 + 보조 콜(vision·별칭) 빈 조립. 역할별 모델 키 분리(`mocha.vision.model`·`mocha.alias.model`). |
| `AgentConfig` | @Configuration | 에이전트 루프 드라이버(`mocha.agent.model`·tool 호출 상한)와 트랜스크립트(턴 상한·TTL) 빈 조립. |
| `RenderConfig` | @Configuration | Thymeleaf 오프라인 템플릿 엔진 + 렌더러 빈 조립(`mocha.artifact.dir`·테마). |
| `SlackConfig` | @Configuration | Slack 송신용 MethodsClient 빈(봇 토큰). 수신(Socket Mode)은 게이트웨이가 앱 토큰으로 별도 배선. |
| `MochaObjectMapper` | final class | 도메인 JSON 직렬화 규칙의 단일 출처 — snake_case, 타임존 오프셋 보존. 저장소·LLM 클라이언트·테스트가 공유. |
| `ImageFormat` | enum | 매직바이트 이미지 포맷 판별(JPEG/PNG/GIF/WebP=vision 지원, HEIC=썸네일 대체, UNKNOWN=거부). |
| `MochaApplication` | class | Spring Boot 진입점. `--rerender` 인자 시 rerender 프로파일(리렌더 후 종료), 아니면 Slack 상주 모드. |

---

## 4. 기능별 흐름 그래프

### 4.1 신규 기록: 텍스트 수신 → 미리보기 (FR-1·2·3·14·22)

"커피베라 예가체프 마셨는데 새콤하고 좋았다" 한 줄이 미리보기에 도달하기까지.

```mermaid
flowchart TB
    A[Slack 메시지 수신] --> B[SlackGateway<br/>즉시 ack · 봇 메시지 필터 · 백그라운드 dispatch]
    B --> C[AgentConversationRouter.onMessage]
    C --> D{먼저 온 사진 버퍼가<br/>윈도우 안에 있나?}
    D -->|예| E[SlackPhotoIntake.absorbFreshBuffer<br/>스테이징 사진 흡수]
    E --> F[PhotoInfoExtractor.extract<br/>OCR 전처리 1콜 — 루프 밖]
    D -->|아니오| G
    F --> G[AgentContextAssembler.assemble<br/>시스템 프롬프트 + 트랜스크립트 + pending + OCR + today]
    G --> H[OpenAiAgentClient.runTurn<br/>에이전트 루프 시작]

    H --> I{모델 응답}
    I -->|web_search 내장 tool| I2[검색 보강<br/>로스터리 공식 우선 · 추측 금지] --> I
    I -->|list_notes / get_note| I3[NoteLookupTools<br/>기존 노트와 매칭 판정 — 별칭 포함 대조] --> I
    I -->|propose_record| J[ProposalTools.executeProposeRecord]
    I -->|tool 호출 없음| K[최종 텍스트만 — 잡담·되묻기]

    J --> L[ProposalValidator.validateRecord<br/>rating·source·레시피·단일 대기 검증]
    L -->|거부| M["오류 사유를 tool 결과로 반환<br/>→ 에이전트가 루프 안에서 정정"] --> I
    L -->|통과| N[PendingStore.put — pending 생성<br/>PreviewMessenger.publish — 미리보기+버튼 전송]
    N --> O[트랜스크립트 접힘<br/>PROPOSAL_ACCEPTED]
    O --> P[에이전트 최종 텍스트 응답]
    K --> P
    P --> Q[SlackResponder.post → Slack]
```

- tool 호출 수가 상한(`mocha.agent.max-tool-calls`)에 닿거나 LLM 호출이 실패하면 `AgentException` → 라우터의 결정론 폴백(pending·노트 무변화, "다시 보내달라" 안내, 원문은 로그 보존).
- pending이 이미 있는데 **다른 커피**의 새 기록이 오면 서버가 제안을 거부하고 "먼저 저장/취소" 사유를 tool 결과로 돌려준다(단일 대기 원칙). **같은 커피**의 재호출은 pending 갱신 경로로 통과한다 — 이것이 곧 pending 수정(FR-5) 메커니즘.

### 4.2 사진 수신·버퍼링·OCR (FR-10·19)

```mermaid
flowchart TB
    A[사진 파일 이벤트] --> B[SlackGateway<br/>file_shared는 no-op · 이미지 MIME만 선별]
    B --> C[AgentConversationRouter.onMedia] --> D[SlackPhotoIntake.receive]
    D --> E[PhotoDownloader.download<br/>봇 토큰 인증 GET]
    E --> F{ImageFormat.detect<br/>매직바이트 판별}
    F -->|JPEG·PNG·GIF·WebP| G[원본 스테이징<br/>data/photos/.staging/userId/]
    F -->|HEIC| H[Slack 썸네일 다운로드<br/>최대 해상도 우선 → 재검증 후 스테이징]
    F -->|미지원| I[그 사진만 버림<br/>지원하지 않는 포맷 안내]
    G --> J{pending 존재?}
    H --> J
    J -->|예| K[스테이징만 — 커밋 시 대상 엔트리 아카이브로]
    J -->|아니오| L[PhotoBufferStore에 버퍼링<br/>lastMediaAt 갱신]
    L --> M[이후 텍스트가 윈도우 내 도착 시<br/>4.1의 버퍼 흡수 → OCR로 이어짐]
```

- OCR은 에이전트 tool이 아니라 루프 전 결정론 전처리다: 스테이징 바이트를 `data:` URI로 인코딩해 vision 1콜, 결과(`VisionExtraction`)는 에이전트 컨텍스트에 주입되어 `source=photo` 값과 매칭 재료가 된다. 못 읽으면 첨부로만 처리(흐름 불변).
- 앱 시작 시 `StagingSweeper`가 pending·버퍼 어디에도 안 걸린 고아 스테이징만 청소한다.

### 4.3 [저장]/[취소] 버튼 커밋 (FR-4·6·7·16)

노트 파일이 실제로 쓰이는 유일한 순간. 에이전트를 거치지 않는 결정론 경로다.

```mermaid
flowchart TB
    A["[저장] 버튼 클릭"] --> B[SlackGateway → Router.onAction<br/>action_id = mocha_save]
    B --> C[SlackCommitHandler.confirmSave]
    C --> D{pending 유효?<br/>존재 · TTL · 필수 필드}
    D -->|아니오| E[만료·파손 안내 — 저장 없음]
    D -->|예, record 모드| F[PhotoStore.commit<br/>스테이징 → photos/slug/date/ 아카이브]
    F --> G{match = new?}
    G -->|예 — 신규 노트| H[AliasGenerator.generate<br/>별칭 LLM 1콜 — 실패해도 빈 별칭으로 저장 계속]
    G -->|아니오 — 기존 노트| I
    H --> I[NoteRepository.upsertEntry<br/>같은 날짜=갱신 · 다른 날짜=추가<br/>기존 노트면 관측 표기를 별칭에 축적]
    D -->|예, edit 모드| J[NoteRepository.applyEdit<br/>엔트리 갱신 · 날짜 이동 시 덮어쓰기<br/>+ 옛 카드 삭제 · 사진 폴더 이동]
    I --> K[pending · 버퍼 clear]
    J --> K
    K --> L[NoteRenderer.renderEntryCard<br/>그 엔트리 카드만 증분 렌더 + 인덱스 갱신]
    L --> M{렌더·업로드 성공?}
    M -->|예| N[SlackResponder.postImage<br/>카드 JPG를 채널에 업로드]
    M -->|아니오| O[안내 텍스트 폴백<br/>저장은 이미 커밋 — 되돌리지 않음]
    N --> P[finalizePreview — 버튼 소진<br/>저장 완료 문구로 교체]
    O --> P
    P --> Q[트랜스크립트 접힘 SAVE_COMMIT]

    R["[취소] 버튼 클릭"] --> S[SlackCommitHandler.cancel<br/>pending·스테이징·버퍼 폐기 — 노트 무변화]
    S --> T[취소 안내 + 버튼 소진 + 접힘 CANCEL_COMMIT]
```

### 4.4 노트 검색·카드 재전송 (FR-20)

"저번에 마신 예가체프 있잖아" — 데이터를 바꾸지 않는 읽기 흐름.

```mermaid
flowchart TB
    A[검색 발화 수신] --> B[에이전트 턴 — 4.1과 동일 진입]
    B --> C[list_notes<br/>전체 노트 메타 + 별칭으로 후보 대조<br/>상대 날짜는 today 기준 해석]
    C --> D{후보 수}
    D -->|단일 매치| E[send_entry_card<br/>기존 카드 JPG 존재 → 그대로 전송<br/>부재 시에만 증분 렌더]
    D -->|복수 후보| F[텍스트 목록 제시<br/>커피명·로스터리·최근 시음일]
    F --> G["사용자: 두 번째<br/>→ 트랜스크립트 문맥으로 해석 → E"]
    D -->|없음| H[구체 단서를 자연어로 되묻기]
    E --> I[카드 이미지 채널 도착]
```

- 검색 중에도 확인 대기 중인 pending은 절대 변하지 않는다(읽기 tool은 파일을 못 바꿈 — 격리).

### 4.5 저장된 노트 수정 (FR-21)

"그거 날짜 엊그제로 바꿔줘" — 기존 노트를 고치는 ✏️ 수정 세션.

```mermaid
flowchart TB
    A[수정 발화 수신] --> B[에이전트 턴]
    B --> C[list_notes / get_note로<br/>대상 노트·엔트리 확정]
    C --> D{대상 명확?}
    D -->|모호 — 후보·날짜 복수| E[자연어 되묻기<br/>트랜스크립트가 왕복 문맥 유지] --> C
    D -->|명확| F[propose_edit 호출<br/>patch에 커피명 필드 자체가 없음 — 이름 변경 구조적 차단]
    F --> G[ProposalValidator.validateEdit<br/>대상 실존 확인 · new_date 충돌 계산은 ProposalTools 몫]
    G -->|거부| H[오류 사유 tool 결과 → 루프 내 정정] --> F
    G -->|통과| I[pending mode=edit 생성<br/>날짜 충돌 시 덮어쓰기 경고 포함<br/>✏️ 미리보기 + 버튼 전송]
    I --> J["이후 수정 발화는 propose_edit 재호출로<br/>draft에 누적 patch (4.1의 갱신 경로)"]
    J --> K["[저장] → 4.3 edit 모드 커밋<br/>[취소] → 원본 바이트 단위 무변화"]
```

### 4.6 전체 리렌더 (NFR-3)

파생물을 지웠거나 디자인을 바꿨을 때 JSON만으로 복구하는 CLI 흐름.

```mermaid
flowchart LR
    A["java -jar mocha --rerender"] --> B[MochaApplication<br/>rerender 프로파일 활성<br/>→ SlackGateway 비활성]
    B --> C[RerenderRunner.run] --> D[NoteRenderer.renderAll]
    D --> E[폰트·마스코트 자산 복사]
    D --> F[index.html 재작성<br/>전 엔트리 최신순 행 목록]
    D --> G[엔트리마다 카드 HTML 조판<br/>→ Chromium 래스터화 → JPG]
    D --> H[고아 카드 정리<br/>JSON에 없는 카드 파일 삭제]
    H --> I[System.exit — 종료]
```

### 4.7 트랜스크립트 생애주기 (FR-23)

트랜스크립트가 왜 존재하고 왜 이렇게 접히는지의 전제(무상태 API·결정론 접힘)는 §2.1 참조.

```mermaid
flowchart TB
    A[에이전트 턴 시작<br/>view로 문맥 조회] --> B{턴 결과}
    B -->|제안 성공| C[접힘 PROPOSAL_ACCEPTED<br/>이후 문맥은 pending draft가 대신]
    B -->|일반 응답 — 잡담·되묻기| D[append — 턴 쌍 추가<br/>상한 초과 시 오래된 턴부터 드롭]
    E["[저장]/[취소] 커밋"] --> F[접힘 SAVE/CANCEL_COMMIT]
    G[TTL 경과 · 프로세스 재시작] --> H[소멸 — 이전 지시어는<br/>되묻기로 처리]
    D --> A
```

---

## 부록: 파일 레이아웃과 소유 클래스

| 경로 | 내용 | 읽기/쓰기 주체 |
|---|---|---|
| `data/notes/<slug>.json` | 노트 원본 (source of truth) | `JsonFileNoteRepository` |
| `data/pending.json` | 확인 대기 (사용자당 1건) | `JsonFilePendingStore` |
| `data/photo-buffer.json` | 사진 버퍼 상태 | `JsonFilePhotoBufferStore` |
| `data/photos/.staging/<userId>/` | 노트 미확정 사진 임시 보관 | `LocalPhotoStore` |
| `data/photos/<slug>/<date>/` | 확정 사진 아카이브 (JSON 미기록·렌더 미사용) | `LocalPhotoStore` |
| `artifact/index.html` | 엔트리 최신순 인덱스 | `ThymeleafNoteRenderer` |
| `artifact/cards/<slug>/<date>.jpg` | 엔트리 카드 (4:5 JPG) | `ThymeleafNoteRenderer` + `PlaywrightCardImageRenderer` |
| `artifact/fonts/`, `artifact/mascot-face.png` | 렌더 로컬 자산 (CDN 미의존) | `ThymeleafNoteRenderer` |
