package com.changelogengine;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import com.changelogengine.service.AuditJobRepository;

@SpringBootTest
class ChangelogEngineBackendApplicationTests {

    @MockitoBean
    private AuditJobRepository jobs;

    @Test
    void contextLoads() {
    }

}
