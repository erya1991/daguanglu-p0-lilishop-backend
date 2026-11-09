package cn.lili.modules.verification.entity.dos;

import cn.lili.mybatis.BaseEntity;
import cn.lili.modules.verification.entity.enums.VerificationSourceEnum;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 验证码资源维护
 * @author Chopper
 * @since 2021/1/30 4:13 下午
 */
@Data
@TableName("li_verification_source")
@Schema(description = "验证码资源维护")
@EqualsAndHashCode(callSuper = false)
public class VerificationSource extends BaseEntity {

    private static final long serialVersionUID = 1L;

    @Schema(description = "名称")
    private String name;

    @Schema(description = "资源地址")
    private String resource;

    @com.baomidou.mybatisplus.annotation.TableField(exist=false)
    private String preview;

    /**
     * @see VerificationSourceEnum
     */
    @Schema(description = "验证码资源类型 SLIDER/SOURCE")
    private String type;
}