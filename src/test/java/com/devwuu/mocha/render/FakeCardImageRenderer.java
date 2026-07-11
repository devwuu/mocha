package com.devwuu.mocha.render;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 결정론적 {@link CardImageRenderer} 테스트 더블 (changes/0002 TΔ2, TΔ4/TΔ5 재사용).
 * <p>실제 Chromium 없이 경계 계약을 검증한다 — 넘어온 (html, baseDir, out)을 기록하고, out에 유효 JPEG
 * 시그니처를 가진 스텁 파일을 써서 다운스트림의 "파일 존재·경로 구조" 단언이 결정론적으로 돌게 한다.
 * <p>{@link #failOnRender}를 켜면 래스터화 실패를 주입해 실패 격리(AC-Δ6/AC-18) 경로를 테스트할 수 있다.
 */
public class FakeCardImageRenderer implements CardImageRenderer {

    /** 한 번의 render 호출 인자 캡처. */
    public record Call(String html, Path baseDir, Path out) {}

    public final List<Call> calls = new ArrayList<>();
    /** true면 out에 JPEG 스텁 파일을 쓴다(다운스트림 존재 단언용). */
    public boolean writeStub = true;
    /** true면 render 시 예외를 던져 실패를 주입한다. */
    public boolean failOnRender = false;

    @Override
    public void render(String html, Path baseDir, Path out) {
        calls.add(new Call(html, baseDir, out));
        if (failOnRender) {
            throw new RuntimeException("주입된 래스터화 실패");
        }
        if (writeStub) {
            try {
                if (out.getParent() != null) {
                    Files.createDirectories(out.getParent());
                }
                // JPEG SOI 시그니처(FF D8 FF) 스텁 — 유효 JPEG 단언을 통과시킨다.
                Files.write(out, new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF});
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }
}
