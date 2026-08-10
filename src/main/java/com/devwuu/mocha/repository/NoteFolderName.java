package com.devwuu.mocha.repository;

import com.devwuu.mocha.domain.Note;
import com.devwuu.mocha.domain.Sourced;

import java.text.Normalizer;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 노트 폴더 접미 생성기 — {@code <id>-<로스터리>-<커피명>}
 * (ref: changes/0028-rdb-storage/delta.md §파일 경로 규약 Q-2·Q-14, AC-Δ9).
 *
 * <p>사진 아카이브와 카드가 같은 세그먼트를 쓴다:
 * <pre>
 *   data/photos/&lt;접미&gt;/&lt;date&gt;/*
 *   artifact/cards/&lt;접미&gt;/&lt;date&gt;-review-&lt;n&gt;.jpg
 * </pre>
 * 둘이 어긋나면 사진과 카드가 다른 폴더로 갈라지므로 조립 규칙을 여기 한곳에 모은다
 * (slug 시절 {@code CardFiles}·{@code LocalPhotoStore}가 slug 문자열을 각자 받아 쓰던 자리다).
 *
 * <p>접미는 <b>사람이 찾기 위한 라벨</b>이고 정본은 DB다. 그래서 다음이 따라온다:
 * <ul>
 *   <li>식별을 보장하는 것은 앞의 {@code id}뿐이다 — 뒤쪽 표기는 새니타이즈·절단으로 얼마든 뭉개져도 된다.</li>
 *   <li>로스터리는 수정 가능하지만(FR-21) 폴더를 이동하지 않는다 — 폴더명은 <b>생성 시점 스냅샷</b>이다.
 *       그러므로 이 생성기는 "지금 이름으로 만들 폴더명"만 답하고, 기존 폴더를 찾는 용도로 쓰면 안 된다.</li>
 * </ul>
 */
public final class NoteFolderName {

    /** 접미 전체(= id 포함) 길이 상한 (delta.md §파일 경로 규약). */
    static final int MAX_LENGTH = 40;

    // 파일시스템 금지문자 — 제거한다(치환하면 없던 구분자가 생긴다).
    private static final Pattern FORBIDDEN = Pattern.compile("[/\\\\:*?\"<>|]");
    // 공백은 '-'로. 연속 공백을 한 번에 접어 '--'를 만들지 않는다(빈 세그먼트 금지와 같은 취지).
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final Pattern DASH_EDGE = Pattern.compile("^-+|-+$");

    private NoteFolderName() {
    }

    /**
     * 폴더 접미를 만든다. 로스터리가 없거나 새니타이즈 후 비면 생략해 {@code <id>-<커피명>}이 된다.
     *
     * @param id         노트 id (DB INSERT 후에만 알 수 있다 — 신규 노트의 사진 이동이 커밋 뒤로 밀리는 이유).
     * @param roastery   로스터리 표기. null·공백·금지문자뿐이면 생략된다.
     * @param coffeeName 커피명 원문 — 로마자화하지 않는다(ADR-28 승계).
     */
    // POLICY: 로스터리가 없으면 세그먼트를 생략한다 — 빈 세그먼트(`123--첼베사`)를 만들지 않는다 (ref: specs/coffee-note-agent/changes/0028-rdb-storage/delta.md#파일-경로-규약 파생 결정 1)
    public static String of(long id, String roastery, String coffeeName) {
        StringBuilder name = new StringBuilder().append(id);
        appendSegment(name, roastery);
        appendSegment(name, coffeeName);
        return truncate(name.toString());
    }

    /**
     * 저장된 노트의 폴더 접미 — 표시값에서 인자를 꺼내는 규칙까지 여기 한곳이 소유한다.
     *
     * <p>호출부가 {@code Sourced}를 각자 풀어 쓰면 "무엇을 세그먼트로 쓰는가"가 카드·사진으로 갈린다
     * (TΔ6c에서 {@code CardFiles}가 이 오버로드로 수렴했다).
     *
     * @throws IllegalArgumentException 저장 전 노트({@code id == null}, D-1)일 때 — 접미의 식별 보장이 곧
     *                                  앞의 id라 대체할 값이 없다.
     */
    public static String of(Note note) {
        if (note.id() == null) {
            throw new IllegalArgumentException(
                    "저장 전 노트는 폴더 접미를 가질 수 없다 — id는 INSERT가 발급한다(D-1)");
        }
        return of(note.id(), Sourced.valueOrNull(note.roastery()), Sourced.valueOrNull(note.coffeeName()));
    }

    /**
     * 저장된 상대 경로에서 폴더 접미를 <b>되읽는다</b> — {@code photos/<접미>/<date>/<파일>}의 둘째 세그먼트
     * (ref: changes/0029 tasks.md TΔ8b).
     *
     * <p>{@link #of}가 <i>"기존 폴더를 찾는 용도로 쓰면 안 된다"</i>고 못 박은 자리의 답이다. 접미는 생성
     * 시점 스냅샷이라 로스터리가 수정된 뒤 재계산하면 옛 폴더와 갈리는데(0028 파생 결정 2가 남긴 한계),
     * {@code note_photo}가 실제로 쓴 경로를 들고 있으므로 <b>계산 대신 읽는다</b>.
     *
     * @param relativePath {@code note_photo.path} 값. 형태가 아니면 빈 Optional(호출부가 재계산으로 수렴).
     */
    public static Optional<String> from(String relativePath) {
        if (relativePath == null) {
            return Optional.empty();
        }
        String[] segments = relativePath.split("/");
        // photos / <접미> / <date> / <파일> — 최소 4조각이고 접미 자리가 비어 있으면 답이 아니다.
        if (segments.length < 4 || !"photos".equals(segments[0]) || segments[1].isBlank()) {
            return Optional.empty();
        }
        return Optional.of(segments[1]);
    }

    private static void appendSegment(StringBuilder name, String raw) {
        String segment = sanitize(raw);
        if (!segment.isEmpty()) {
            name.append('-').append(segment);
        }
    }

    /**
     * NFC → 금지문자 제거 → 공백 접기 → 가장자리 '-' 제거.
     *
     * <p>NFC를 <b>맨 먼저</b> 건다. macOS가 한글 파일명을 자모 분리(NFD)로 저장하므로 정규화하지 않으면
     * "폴더는 있는데 못 찾는" 증상이 나고, 더해서 NFD 한글은 음절당 3코드유닛이라 길이 상한이 표기 기준과
     * 어긋난다 — 정규화 뒤에 재야 40자가 사람이 보는 40자가 된다.
     */
    private static String sanitize(String raw) {
        if (raw == null) {
            return "";
        }
        String normalized = Normalizer.normalize(raw, Normalizer.Form.NFC);
        String cleaned = FORBIDDEN.matcher(normalized).replaceAll("");
        cleaned = WHITESPACE.matcher(cleaned).replaceAll("-");
        return DASH_EDGE.matcher(cleaned).replaceAll("");
    }

    private static String truncate(String name) {
        if (name.length() <= MAX_LENGTH) {
            return name;
        }
        int end = MAX_LENGTH;
        // 서러게이트 쌍 한가운데서 자르면 파일명에 홀로 남은 코드유닛이 박힌다.
        if (Character.isHighSurrogate(name.charAt(end - 1))) {
            end--;
        }
        // 절단면이 세그먼트 경계에 떨어지면 '-'로 끝난다.
        return DASH_EDGE.matcher(name.substring(0, end)).replaceAll("");
    }
}
