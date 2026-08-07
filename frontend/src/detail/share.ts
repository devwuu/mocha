import type { CardType, NoteDetailCup } from '../api'
import { getNoteCard } from '../api'

/**
 * 회차 카드 공유 — 상세 화면 [공유] 버튼의 실체 (changes/0029 TΔ9, OQ-3 ㉡ *"공유 버튼을 누르면 이미지로
 * 저장이 가능하게"*).
 *
 * **공유 단위는 회차다.** 카드가 감상·레시피로 갈린 것은 렌더 단위이지(ADR-59) 사용자가 고르는 축이
 * 아니다 — 구 Slack 배달도 파트를 고르게 하지 않고 그 시음의 카드를 전부 보냈다(FR-16). 파트마다 버튼을
 * 달면 회차 하나에 버튼이 둘이고, 상세 화면이 공유 버튼으로 뒤덮인다.
 *
 * **두 경로가 있고 능력이 가른다**: 공유 시트를 띄울 수 있으면 그리로(폰 — 저장·SNS·메시지가 전부 그
 * 안에 있다), 아니면 내려받기로(데스크톱 브라우저). 후자에서도 *"이미지로 저장"*은 성립한다.
 */

/** 그 회차에 있는 카드 종류 — 없는 파트는 카드가 없다(AC-78). 순서는 감상 → 레시피(렌더 순서와 같다). */
export function cardTypesOf(cup: NoteDetailCup): CardType[] {
  const types: CardType[] = []
  if (cup.review !== null) {
    types.push('taste')
  }
  if (cup.recipe !== null) {
    types.push('recipe')
  }
  return types
}

/**
 * 그 회차의 카드를 전부 받아 공유(또는 내려받기)한다.
 *
 * 첫 장은 서버가 그때 굽는다 — 헤드리스 브라우저 기동을 지므로 초 단위로 느리고, 그동안 화면이 버튼을
 * 잠근다. **두 장을 순차로 받는 것이 의도다**: 첫 요청이 그 엔트리의 카드를 한 번에 채우므로(서버의
 * 캐시 미스 정책) 병렬로 쏘면 같은 엔트리를 두 번 굽는다.
 *
 * @returns 공유 시트가 실제로 열렸으면 `true`, 내려받기로 폴백했으면 `false`.
 * @throws 카드를 받지 못했을 때. 사용자가 공유 시트를 닫은 것(`AbortError`)은 실패가 아니라 정상 종료다.
 */
export async function shareCupCards(
  noteId: number,
  date: string,
  cup: NoteDetailCup,
  cupNumber: number,
): Promise<boolean> {
  const files: File[] = []
  for (const type of cardTypesOf(cup)) {
    const blob = await getNoteCard(noteId, date, type, cupNumber)
    files.push(new File([blob], `${date}-${type}-${cupNumber}.jpg`, { type: 'image/jpeg' }))
  }
  if (files.length === 0) {
    return false
  }

  if (navigator.canShare?.({ files })) {
    try {
      await navigator.share({ files })
      return true
    } catch (error) {
      // 사용자가 시트를 닫은 것은 실패가 아니다 — 조용히 끝낸다.
      if (isAbort(error)) {
        return true
      }
      // 그 밖의 거절(iOS는 제스처와 share 호출 사이가 멀면 NotAllowedError를 던진다 — 첫 굽기가 초 단위라
      // 실제로 닿을 수 있는 경로다)에는 내려받기가 남는다. 여기서 던지면 공유가 통째로 실패한다.
    }
  }
  download(files)
  return false
}

/** 오브젝트 URL을 붙인 링크를 눌러 저장한다. 폴백 경로라 화면에 흔적을 남기지 않는다. */
function download(files: File[]) {
  for (const file of files) {
    const url = URL.createObjectURL(file)
    const link = document.createElement('a')
    link.href = url
    link.download = file.name
    link.click()
    // 즉시 해제하면 사파리에서 내려받기가 시작되기 전에 URL이 죽는다 — 다음 틱까지 살려 둔다.
    setTimeout(() => URL.revokeObjectURL(url), 0)
  }
}

function isAbort(error: unknown): boolean {
  return error instanceof DOMException && error.name === 'AbortError'
}
