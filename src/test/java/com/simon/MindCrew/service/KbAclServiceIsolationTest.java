package com.simon.MindCrew.service;

import com.simon.MindCrew.entity.KbAcl;
import com.simon.MindCrew.entity.KbKnowledgeBase;
import com.simon.MindCrew.entity.KnowledgeCollection;
import com.simon.MindCrew.entity.MedKnowledgeBase;
import com.simon.MindCrew.entity.SysUser;
import com.simon.MindCrew.mapper.KbAclMapper;
import com.simon.MindCrew.mapper.KbKnowledgeBaseMapper;
import com.simon.MindCrew.mapper.KnowledgeCollectionMapper;
import com.simon.MindCrew.mapper.MedKnowledgeBaseMapper;
import com.simon.MindCrew.mapper.SysDepartmentMapper;
import com.simon.MindCrew.mapper.SysUserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class KbAclServiceIsolationTest {

    @BeforeAll
    static void initLambdaCache() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(configuration, "");
        TableInfoHelper.initTableInfo(assistant, KnowledgeCollection.class);
        TableInfoHelper.initTableInfo(assistant, MedKnowledgeBase.class);
        TableInfoHelper.initTableInfo(assistant, KbKnowledgeBase.class);
        TableInfoHelper.initTableInfo(assistant, KbAcl.class);
    }

    private KbAclMapper aclMapper;
    private KbKnowledgeBaseMapper kbMapper;
    private SysUserMapper userMapper;
    private KnowledgeCollectionMapper collectionMapper;
    private MedKnowledgeBaseMapper docMapper;
    private KbAclService service;

    @BeforeEach
    void setUp() {
        aclMapper = mock(KbAclMapper.class);
        kbMapper = mock(KbKnowledgeBaseMapper.class);
        userMapper = mock(SysUserMapper.class);
        collectionMapper = mock(KnowledgeCollectionMapper.class);
        docMapper = mock(MedKnowledgeBaseMapper.class);
        service = new KbAclService(aclMapper, kbMapper, userMapper,
                mock(SysDepartmentMapper.class), collectionMapper, docMapper);
    }

    @Test
    void documentInsideDeniedCollectionCannotFallBackToPublicDocumentVisibility() {
        SysUser user = user(7L, "inherit");
        when(userMapper.selectById(7L)).thenReturn(user);

        KnowledgeCollection denied = new KnowledgeCollection();
        denied.setId(99L);
        denied.setVisibility("scoped");
        when(collectionMapper.selectList(any())).thenReturn(List.of(denied));
        when(aclMapper.selectList(any())).thenReturn(List.of());

        MedKnowledgeBase doc = new MedKnowledgeBase();
        doc.setId(11L);
        doc.setCollectionId(99L);
        doc.setVisibility("public");
        when(docMapper.selectList(any())).thenReturn(List.of(doc));

        assertFalse(service.listAccessibleKbIds(7L).contains(11L));
    }

    @Test
    void overrideModeIgnoresInheritedDocumentAcl() {
        SysUser user = user(7L, "override");
        user.setPositionId(3L);
        when(userMapper.selectById(7L)).thenReturn(user);

        MedKnowledgeBase doc = new MedKnowledgeBase();
        doc.setId(11L);
        when(docMapper.selectById(11L)).thenReturn(doc);
        KbKnowledgeBase legacy = new KbKnowledgeBase();
        legacy.setId(11L);
        legacy.setVisibility("scoped");
        when(kbMapper.selectById(11L)).thenReturn(legacy);

        KbAcl inherited = new KbAcl();
        inherited.setRefType(KbAclService.REF_DOC);
        inherited.setPositionId(3L);
        inherited.setPermission(KbAclService.PERM_READ);
        when(aclMapper.selectList(any())).thenReturn(List.of(inherited));

        assertFalse(service.canAccess(7L, 11L, KbAclService.PERM_READ));
    }

    @Test
    void archivedDocumentAlwaysUsesCollectionAcl() {
        SysUser user = user(7L, "inherit");
        when(userMapper.selectById(7L)).thenReturn(user);

        MedKnowledgeBase doc = new MedKnowledgeBase();
        doc.setId(11L);
        doc.setCollectionId(99L);
        when(docMapper.selectById(11L)).thenReturn(doc);

        KnowledgeCollection collection = new KnowledgeCollection();
        collection.setId(99L);
        collection.setVisibility("public");
        when(collectionMapper.selectById(99L)).thenReturn(collection);

        assertTrue(service.canAccess(7L, 11L, KbAclService.PERM_READ));
    }

    private SysUser user(Long id, String mode) {
        SysUser user = new SysUser();
        user.setId(id);
        user.setRole("user");
        user.setKbScopeMode(mode);
        return user;
    }
}
