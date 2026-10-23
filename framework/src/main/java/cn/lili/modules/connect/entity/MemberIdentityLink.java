package cn.lili.modules.connect.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;
import java.util.Date;

/** Audit references only. No password, phone, proof bearer, WeChat code or Session token. */
@Getter @Setter @TableName("li_member_identity_link")
public class MemberIdentityLink {
    @TableId private String id;
    private String mappingId;
    private String requestId;
    private String sourceMemberId;
    private String targetMemberId;
    private Long beforeVersion;
    private Long afterVersion;
    private String proofReference;
    private String proofKind;
    private String consentVersion;
    private Date consentTime;
    private String sourceSid;
    private String targetSid;
    private String state;
    private Date createTime;
    private Date updateTime;
    @Override public String toString() {return "MemberIdentityLink[REDACTED]";}
}
