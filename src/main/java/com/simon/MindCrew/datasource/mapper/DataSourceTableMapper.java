package com.simon.MindCrew.datasource.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.simon.MindCrew.datasource.entity.DataSourceTable;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface DataSourceTableMapper extends BaseMapper<DataSourceTable> {

    /**
     * 物理删除某数据源的全部表元数据（绕过全局逻辑删除）。
     * 「先删后插」整体替换语义下必须物理删，否则 deleted=1 的旧行仍占用唯一键
     * uk_ds_table(datasource_id, table_name)，再插同名表会报 Duplicate entry。
     */
    @Delete("DELETE FROM data_source_table WHERE datasource_id = #{dsId}")
    int physicalDeleteByDatasourceId(@Param("dsId") Long dsId);

    /** 物理查询某数据源已存在的所有表名（含逻辑删除行）· 供合并同步查重，避免撞唯一键 */
    @Select("SELECT table_name FROM data_source_table WHERE datasource_id = #{dsId}")
    List<String> selectAllTableNamesRaw(@Param("dsId") Long dsId);
}
