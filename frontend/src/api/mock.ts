/**
 * 아직 서지 않은 API의 임시 구현 (changes/0029 TΔ13b).
 *
 * **mock은 임시방편이 아니라 계약 초안이다**(D-10 ③). 여기 있는 함수의 시그니처·반환 형태가 곧
 * `note-update.contract.json`이고, **TΔ5b가 그것을 구현하면 `index.ts`의 재수출 세 줄이 `./http`로
 * 옮겨가며 이 파일이 다시 사라진다** — TΔ5a에서 목록·상세 mock이 그렇게 지워졌다. 계약의 정본은 그 사이에도
 * mock이 아니라 `src/test/resources/contract/`의 JSON 파일이다.
 *
 * **쓰기 3종만 산다.** 조회는 이미 실물이므로(`getNoteDetail`) 여기서 그것을 그대로 부른다 — mock이 자체
 * 픽스처를 들면 수정 화면이 실 데이터가 아닌 노트를 고치게 되고, 그러면 화면 판정이 아무것도 증명하지 못한다.
 *
 * **상태를 남기지 않는다**: 매 호출이 서버의 현재 노트를 다시 읽어 그 위에 요청을 얹은 결과를 돌려준다.
 * 그래서 화면은 저장 직후의 모습을 정확히 보지만 새로고침하면 되돌아간다 — 서버가 아직 쓰지 않으니 당연하고,
 * mock이 저장을 흉내내 그 사실을 가리지 않는 편이 낫다.
 */
import type { NoteDetail, NoteDetailEntry, NoteEntryUpdate, NoteMetaUpdate } from './contract'
import { getNoteDetail } from './http'

/** `PATCH /api/notes/{id}` — 커피명·엔트리를 뺀 사실 갱신. */
export async function patchNoteMeta(noteId: number, body: NoteMetaUpdate): Promise<NoteDetail> {
  const note = await getNoteDetail(noteId)
  return { ...note, ...body }
}

/**
 * `PATCH /api/notes/{id}/entries/{date}` — 회차 교체 + 날짜 이동.
 *
 * **이동처에 기록이 있으면 그날의 회차 뒤로 합친다**(D-12) — 서버가 질 규칙을 여기서 흉내내는 것은 화면의
 * 병합 안내가 실제 결과와 맞는지 눈으로 보기 위해서고, **규칙의 소유자는 `NoteTxService.replaceEntry`**다
 * (TΔ5b). 이 파일이 지워질 때 함께 지워지는 근사치다.
 */
export async function patchNoteEntry(
  noteId: number,
  targetDate: string,
  body: NoteEntryUpdate,
): Promise<NoteDetail> {
  const note = await getNoteDetail(noteId)
  const target = note.entries.find((entry) => entry.date === targetDate)
  if (target === undefined) {
    throw new Error('HTTP 404')
  }
  // 사진은 엔트리를 따라 옮겨간다(FR-10) — 다만 URL의 날짜 세그먼트는 서버가 파일을 옮기며 바뀌므로
  // mock의 URL은 옛 날짜 그대로다. 경로 규약을 클라이언트가 조립하지 않는다는 계약(ADR-75)이 여기서도 산다.
  const moved: NoteDetailEntry = { date: body.date, brews: body.brews, photos: target.photos }
  const rest = note.entries.filter((entry) => entry !== target)
  const conflict = rest.find((entry) => entry.date === moved.date)

  const entries =
    conflict === undefined
      ? [...rest, moved]
      : rest.map((entry) =>
          entry === conflict
            ? { date: entry.date, brews: [...entry.brews, ...moved.brews], photos: [...entry.photos, ...moved.photos] }
            : entry,
        )
  return { ...note, entries: entries.sort((a, b) => a.date.localeCompare(b.date)) }
}

/** `DELETE /api/notes/{id}` — 본문 없는 204. hard delete라 되돌릴 것이 없다(AC-6). */
export async function deleteNote(noteId: number): Promise<void> {
  await getNoteDetail(noteId)
}
