package cn.lili.modules.member.mapper;

import cn.lili.modules.member.profile.ResidentProfileRow;
import org.apache.ibatis.annotations.*;
import java.util.List;

/** B04-gated SQL only. Mobile UNIQUE must cover legacy writers as well as this path. */
public interface ResidentProfileMapper {
    String COLUMNS = "id,nick_name AS nickname,face AS avatar,mobile,profile_version AS version,update_time,"
            + "disabled,delete_flag,phone_request_id,phone_request_hash";
    @Select("SELECT " + COLUMNS + " FROM li_member WHERE id=#{id}")
    ResidentProfileRow read(String id);
    @Select("SELECT " + COLUMNS + " FROM li_member WHERE id=#{id} FOR UPDATE")
    ResidentProfileRow lock(String id);
    @Select("SELECT id FROM li_member WHERE mobile=#{phone} LIMIT 2 FOR UPDATE")
    List<String> phoneOwners(String phone);
    @Update("UPDATE li_member SET nick_name=#{nickname},face=#{avatar},profile_version=profile_version+1,"
            + "update_time=CURRENT_TIMESTAMP(6) WHERE id=#{id} AND profile_version=#{version} AND disabled=1 AND delete_flag=0")
    int edit(@Param("id") String id, @Param("version") long version,
             @Param("nickname") String nickname, @Param("avatar") String avatar);
    @Update("UPDATE li_member SET mobile=#{phone},phone_request_id=#{requestId},phone_request_hash=#{hash},"
            + "profile_version=profile_version+1,update_time=CURRENT_TIMESTAMP(6) "
            + "WHERE id=#{id} AND profile_version=#{version} AND disabled=1 AND delete_flag=0")
    int phone(@Param("id") String id, @Param("version") long version, @Param("phone") String phone,
              @Param("requestId") String requestId, @Param("hash") String hash);
}
