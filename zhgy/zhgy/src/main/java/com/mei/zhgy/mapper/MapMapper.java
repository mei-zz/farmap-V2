package com.mei.zhgy.mapper;

import com.mei.zhgy.dto.MapTreeDTO;
import com.mei.zhgy.vo.MapFarmVO;
import com.mei.zhgy.vo.MapTreeVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface MapMapper {

    List<MapTreeVO> selectTree(MapTreeDTO mapTree);

    /**
     * 这个接口，直接查询了全部的farm信息，slide的信息暂时没有用上
     * @param map
     * @return
     */
    @Select("select * from farm")
    List<MapFarmVO> selectFarm(MapTreeDTO map);
}
