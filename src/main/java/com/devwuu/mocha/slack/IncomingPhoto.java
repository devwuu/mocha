package com.devwuu.mocha.slack;

import java.util.List;

/**
 * Slack 파일 공유 이벤트의 사진 1건에서 필요한 필드만 뽑아낸 내부 표현 (ref: tasks T4-2, FR-10).
 * <p>{@link SlackGateway}가 Slack {@code File} 타입을 라우팅 경계 밖으로 새지 않게 변환한 값객체다 —
 * 다운로드는 오케스트레이션이 {@link PhotoDownloader}로 수행한다.
 * <p>HEIC 등 vision 미지원 원본은 {@code thumbnailUrls}(Slack이 제공하는 vision 지원 포맷 썸네일, 실측 PNG)로
 * 대체 다운로드한다(ADR-29, TΔ3, changes/0013). {@code mimetype}은 매직바이트 판별을 보강하는 힌트다.
 *
 * @param urlPrivate    bot token 인증이 필요한 원본 URL(url_private_download 우선).
 * @param filename      원본 파일명 — 스테이징 시 안전 문자로 정규화된다.
 * @param mimetype      Slack 메타 mimetype(nullable) — HEIC 판별 힌트(매직바이트가 확정, 이건 보강).
 * @param thumbnailUrls Slack 썸네일 URL 후보, <b>최대 해상도 우선</b> 정렬. HEIC 대체 다운로드에 쓴다(부재 시 빈 목록).
 */
public record IncomingPhoto(String urlPrivate, String filename, String mimetype, List<String> thumbnailUrls) {

    public IncomingPhoto {
        thumbnailUrls = thumbnailUrls == null ? List.of() : List.copyOf(thumbnailUrls);
    }
}
