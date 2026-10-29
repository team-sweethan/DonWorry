package com.donworry.identity.api;

import java.util.List;

public interface MerchantDirectoryReader {
    // 경보·Case 화면이 현재·과거 가맹점을 함께 표시할 수 있도록 ACTIVE·INACTIVE를 모두 반환한다.
    List<MerchantDirectoryEntry> findAll();
}
