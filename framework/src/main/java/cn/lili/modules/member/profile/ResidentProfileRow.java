package cn.lili.modules.member.profile;

import lombok.Getter;
import lombok.Setter;
import java.util.Date;

/** Explicit projection: no password column and no BaseEntity implicit schema assumptions. */
@Getter @Setter
public class ResidentProfileRow {
    private String id;
    private String nickname;
    private String avatar;
    private String mobile;
    private Long version;
    private Date updateTime;
    private Boolean disabled;
    private Boolean deleteFlag;
    private String phoneRequestId;
    private String phoneRequestHash;
    @Override public String toString() { return "ResidentProfileRow[REDACTED]"; }
}
