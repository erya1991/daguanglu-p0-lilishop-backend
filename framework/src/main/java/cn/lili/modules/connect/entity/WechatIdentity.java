package cn.lili.modules.connect.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;
import java.util.Date;

/** New mapping only. Does not reinterpret, delete or asynchronously replace legacy li_connect. */
@Getter @Setter
@TableName("li_wechat_identity")
public class WechatIdentity {
    @TableId private String id;
    private String appId;
    private String openId;
    private String memberId;
    private Long bindingVersion;
    private String unionId;
    /** Only ACTIVE authorizes. PENDING/unknown/null fail closed; no handoff writer in P03-1. */
    private String bindingState;
    private Date createTime;
    private Date updateTime;
    @Override public String toString() { return "WechatIdentityMapping[REDACTED]"; }
}
