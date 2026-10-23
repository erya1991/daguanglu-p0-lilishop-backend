package cn.lili.modules.connect.mapper;

import cn.lili.modules.connect.entity.MemberIdentityLink;
import cn.lili.modules.connect.entity.WechatIdentity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.*;
import java.util.List;

public interface MemberIdentityLinkMapper extends BaseMapper<MemberIdentityLink> {
    @Select("SELECT * FROM li_wechat_identity WHERE id=#{id} FOR UPDATE")
    WechatIdentity lockMapping(String id);
    @Select("SELECT * FROM li_member_identity_link WHERE mapping_id=#{mapping} AND request_id=#{request} LIMIT 2")
    List<MemberIdentityLink> request(@Param("mapping") String mapping,@Param("request") String request);
    @Select("SELECT * FROM li_member_identity_link WHERE mapping_id=#{mapping} AND state='PENDING' LIMIT 2")
    List<MemberIdentityLink> pending(String mapping);
    @Update("UPDATE li_wechat_identity SET member_id=#{target},binding_version=binding_version+1,binding_state='PENDING',"
            +"update_time=CURRENT_TIMESTAMP(6) WHERE id=#{id} AND member_id=#{source} AND binding_version=#{version} AND binding_state='ACTIVE'")
    int switchMapping(@Param("id") String id,@Param("source") String source,@Param("target") String target,@Param("version") long version);
    @Update("UPDATE li_wechat_identity SET binding_state='ACTIVE',update_time=CURRENT_TIMESTAMP(6) "
            +"WHERE id=#{id} AND member_id=#{target} AND binding_version=#{version} AND binding_state='PENDING'")
    int activate(@Param("id") String id,@Param("target") String target,@Param("version") long version);
    @Update("UPDATE li_member_identity_link SET state='DONE',target_sid=#{sid},update_time=CURRENT_TIMESTAMP(6) WHERE id=#{id} AND state='PENDING'")
    int done(@Param("id") String id,@Param("sid") String sid);
    // Actual native relationships. Missing tables/columns throw; never treated as empty history.
    // All reads execute under SERIALIZABLE; B04 must verify indexed predicates/locks with native writers.
    @Select("""
        SELECT
          (SELECT COUNT(*) FROM li_order WHERE member_id=#{id}) +
          (SELECT COUNT(*) FROM li_trade WHERE member_id=#{id}) +
          (SELECT COUNT(*) FROM li_store WHERE member_id=#{id}) +
          (SELECT COUNT(*) FROM li_clerk WHERE member_id=#{id}) +
          (SELECT COUNT(*) FROM li_member_wallet WHERE member_id=#{id} AND
              (member_wallet IS NULL OR member_frozen_wallet IS NULL OR member_wallet<>0 OR member_frozen_wallet<>0)) +
          (SELECT COUNT(*) FROM li_wallet_log WHERE member_id=#{id}) +
          (SELECT COUNT(*) FROM li_recharge WHERE member_id=#{id}) +
          (SELECT COUNT(*) FROM li_member_withdraw_apply WHERE member_id=#{id}) +
          (SELECT COUNT(*) FROM li_member_coupon WHERE member_id=#{id}) +
          (SELECT COUNT(*) FROM li_member_points_history WHERE member_id=#{id}) +
          (SELECT COUNT(*) FROM li_member_grade_benefit_grant WHERE member_id=#{id}) +
          (SELECT COUNT(*) FROM li_distribution WHERE member_id=#{id}) +
          (SELECT COUNT(*) FROM li_distribution_customer_relation WHERE member_id=#{id}) +
          (SELECT COUNT(*) FROM li_gift_card_cash_credential WHERE member_id=#{id} OR activate_member_id=#{id}) +
          (SELECT COUNT(*) FROM li_member WHERE id=#{id} AND
              (COALESCE(point,0)<>0 OR COALESCE(total_point,0)<>0 OR COALESCE(experience,0)<>0 OR have_store=1 OR COALESCE(store_id,'')<>''))
        """)
    Long history(String id);
}
