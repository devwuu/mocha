import type {
  AgentTurnRequest,
  AgentTurnResponse,
  Draft,
  NoteCommitRequest,
  NoteCommitResponse,
} from './contract'

/**
 * 턴·저장 API의 mock 구현 (changes/0029 TΔ10).
 *
 * 슬라이스 규율상 화면이 API보다 먼저다(delta.md D-10 ③). TΔ6이 `POST /api/agent/turn`·`POST /api/notes`를
 * 세울 때까지 화면은 이 mock을 상대로 돈다 — **에이전트 흉내가 목적이 아니라, 계약이 화면을 실제로 굴릴 수
 * 있는 모양인지 확인하는 것이 목적이다**. 그래서 응답은 고정 픽스처이고 발화 내용을 해석하지 않는다.
 *
 * 지키는 것은 계약뿐이다: 첫 턴(draft 없음)은 제안을 돌려주고, draft를 실은 턴은 **받은 draft를 그대로
 * 되돌려준다** — 사용자가 폼에서 고친 값이 서버 왕복으로 되돌아가지 않는다는 것이 이 델타의 핵심 방어선
 * (V-6)이고, 화면이 그 전제 위에서 도는지가 여기서 드러난다.
 */

// 실제 턴은 모델 호출이라 즉답이 아니다 — 타이핑 표시가 실제로 보이는지 확인하려면 지연이 필요하다.
const MOCK_LATENCY_MS = 700

const PROPOSED_DRAFT: Draft = {
  note: {
    id: null,
    coffee_name: { value: '봄맞이 블렌드', source: 'photo' },
    roastery: { value: '커피베라', source: 'photo' },
    beans: [
      { description: { value: '에티오피아 구지', source: 'search' }, process: { value: '내추럴', source: 'search' } },
      { description: { value: '브라질 세하도', source: 'search' }, process: { value: '펄프드 내추럴', source: 'search' } },
    ],
    roast_level: { value: '미디엄', source: 'search' },
    official_notes: { value: ['다크초콜릿', '오렌지', '캐러멜'], source: 'search' },
    sources: ['https://example.test/spring-blend'],
    entries: [
      {
        date: '2026-08-01',
        brews: [
          {
            recipe: {
              method: '에스프레소',
              dose_g: 18,
              water_ml: null,
              yield_ml: 36,
              time_sec: 28,
              temp_c: null,
              grind: '90클릭 (매버릭 2.0)',
              machine: null,
              pouring: null,
              feedback: '뒷맛이 쓴 걸 보면 과다추출 같음',
            },
            tasting: {
              my_taste: '뒷맛이 씀',
              my_taste_original: '에스프레소로 내렸는데 뒷맛이 써요',
              rating: '맛은 있는데 내스타일은 아님',
            },
          },
        ],
        updated_at: null,
      },
    ],
    created_at: null,
    updated_at: null,
  },
  match: { type: 'new' },
}

let nextNoteId = 12

export async function postAgentTurn(request: AgentTurnRequest): Promise<AgentTurnResponse> {
  await delay(MOCK_LATENCY_MS)
  if (request.draft === null) {
    return {
      reply: '커피베라 · 봄맞이 블렌드로 읽었어요. 18g / 28초면 살짝 과다추출이에요. 폼에서 확인해 주세요.',
      draft: PROPOSED_DRAFT,
    }
  }
  // 받은 draft를 그대로 돌려준다 — mock에는 반영할 판단이 없고, 임의로 값을 바꾸면 "폼에서 고친 값이
  // 되돌아가는" 바로 그 실패를 mock이 스스로 만들어 화면 검증이 무의미해진다.
  return {
    reply: '폼 내용 그대로 두고 반영할 게 없었어요. 더 알려주실 게 있나요?',
    draft: request.draft,
  }
}

export async function postNoteCommit(request: NoteCommitRequest): Promise<NoteCommitResponse> {
  await delay(MOCK_LATENCY_MS)
  return { note_id: request.note.id ?? nextNoteId++ }
}

function delay(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms))
}
