package com.simon.MindCrew.service.rag;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class RagCacheScopeTest {

    @Test
    void emptyAclScopeNeverSharesGlobalCacheNamespace() {
        assertEquals("global", RagCacheService.scopeKey(null));
        assertEquals("none", RagCacheService.scopeKey(List.of()));
        assertNotEquals(RagCacheService.scopeKey(null), RagCacheService.scopeKey(List.of()));
    }

    @Test
    void scopeKeyIsStableAcrossOrderAndDuplicates() {
        assertEquals("2,7", RagCacheService.scopeKey(List.of(7L, 2L, 7L)));
    }
}
