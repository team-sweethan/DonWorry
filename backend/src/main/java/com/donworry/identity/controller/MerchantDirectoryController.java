package com.donworry.identity.controller;

import com.donworry.identity.api.MerchantDirectoryEntry;
import com.donworry.identity.api.MerchantDirectoryReader;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/merchants")
@RequiredArgsConstructor
public class MerchantDirectoryController {
    private final MerchantDirectoryReader merchantDirectory;

    @GetMapping
    public List<MerchantDirectoryEntry> list() {
        return merchantDirectory.findAll();
    }
}
