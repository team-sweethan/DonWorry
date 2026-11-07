package com.donworry.identity.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

// 브라우저 원문 → "브라우저 · 운영체제" 이름 (#643, 내부자 탐지 INS-07)
class UserAgentDeviceTests {
    private static final String CHROME_141_WINDOWS =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36";
    private static final String CHROME_142_WINDOWS =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/142.0.7444.60 Safari/537.36";
    private static final String EDGE_142_WINDOWS = CHROME_142_WINDOWS + " Edg/142.0.3595.53";

    // 브라우저 업데이트로 버전만 바뀌어도 다른 기기가 되면 "처음 보는 브라우저" 높음 경보가 정상 직원에게 뜬다.
    // Edge 문자열에도 Chrome이 들어 있어 Chrome으로 잘못 묶이면 실제로 바뀐 브라우저를 놓친다
    @Test
    void label_versionOnlyChange_sameDevice() {
        assertThat(UserAgentDevice.label(CHROME_141_WINDOWS))
                .isEqualTo(UserAgentDevice.label(CHROME_142_WINDOWS))
                .isEqualTo("Chrome · Windows");
        assertThat(UserAgentDevice.label(EDGE_142_WINDOWS)).isEqualTo("Edge · Windows");
    }
}
