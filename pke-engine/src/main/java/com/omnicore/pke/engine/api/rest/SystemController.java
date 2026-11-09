package com.omnicore.pke.engine.api.rest;

import com.omnicore.pke.engine.ops.SystemInfo;
import com.omnicore.pke.engine.ops.SystemInfoService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/system")
public class SystemController {

    private final SystemInfoService systemInfoService;

    public SystemController(SystemInfoService systemInfoService) {
        this.systemInfoService = systemInfoService;
    }

    @GetMapping("/info")
    public SystemInfo info() {
        return systemInfoService.info();
    }
}
