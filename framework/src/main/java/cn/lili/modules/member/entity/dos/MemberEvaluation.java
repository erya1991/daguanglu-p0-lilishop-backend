package cn.lili.modules.member.entity.dos;

import cn.hutool.core.text.CharSequenceUtil;
import cn.lili.common.enums.SwitchEnum;
import cn.lili.common.security.sensitive.Sensitive;
import cn.lili.common.security.sensitive.enums.SensitiveStrategy;
import cn.lili.modules.goods.entity.dos.GoodsSku;
import cn.lili.modules.member.entity.dto.MemberEvaluationDTO;
import cn.lili.modules.order.order.entity.dos.Order;
import cn.lili.mybatis.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.springframework.beans.BeanUtils;

/**
 * 客户商品评价
 *
 * @author Bulbasaur
 * @since 2020-02-25 14:10:16
 */
@Data
@TableName("li_member_evaluation")
@Schema(description = "客户商品评价")
@NoArgsConstructor
@EqualsAndHashCode(callSuper = false)
public class MemberEvaluation extends BaseEntity {

    private static final long serialVersionUID = 1L;
    @Schema(description="P0单一商品星级；历史无星保持空")
    private Integer star;
    private String p0OrderItemId;
    private Long p0Version;

    @Schema(description = "客户ID")
    private String memberId;

    @NotNull
    @Schema(description = "店铺ID")
    private String storeId;

    @NotNull
    @Schema(description = "店铺名称")
    private String storeName;

    @NotNull
    @Schema(description = "商品ID")
    private String goodsId;

    @NotNull
    @Schema(description = " SKU ID")
    private String skuId;

    @NotNull
    @Schema(description = "客户名称")
    @Sensitive(strategy = SensitiveStrategy.PHONE)
    private String memberName;

    @NotNull
    @Schema(description = "客户头像")
    private String memberProfile;

    @NotNull
    @Schema(description = "商品名称")
    private String goodsName;

    @NotNull
    @Schema(description = "商品图片")
    private String goodsImage;

    @NotNull
    @Schema(description = "订单号")
    private String orderNo;

    @NotNull
    @Schema(description = "好中差评 , GOOD：好评，MODERATE：中评，WORSE：差评", allowableValues = "GOOD,MODERATE,WORSE")
    private String grade;

    @NotNull
    @Schema(description = " 评价内容")
    private String content;

    @Schema(description = "评价图片")
    private String images;

    @NotNull
    @Schema(description = "状态  OPEN 正常 ,CLOSE 关闭 ")
    private String status;

    @Schema(description = "评价回复")
    private String reply;

    @Schema(description = "评价回复图片")
    private String replyImage;

    @Schema(description = "评论是否有图片 true 有 ,false 没有")
    private Boolean haveImage;

    @Schema(description = "回复是否有图片 true 有 ,false 没有")
    private Boolean haveReplyImage;

    @Schema(description = "回复状态")
    private boolean replyStatus;

    @Schema(description = "是否置顶")
    private Boolean top;

    @Schema(description = "物流评分")
    private Integer deliveryScore;

    @Schema(description = "服务评分")
    private Integer serviceScore;

    @Schema(description = "描述评分")
    private Integer descriptionScore;


    public MemberEvaluation(MemberEvaluationDTO memberEvaluationDTO, GoodsSku goodsSku, Member member, Order order) {
        //复制评价信息
        BeanUtils.copyProperties(memberEvaluationDTO, this);
        //设置客户
        this.memberId = member.getId();
        //客户名称
        this.memberName = member.getNickName();
        //设置客户头像
        this.memberProfile = member.getFace();
        //商品名称
        this.goodsName = goodsSku.getGoodsName();
        //商品图片
        this.goodsImage = goodsSku.getThumbnail();
        //设置店铺ID
        this.storeId = order.getStoreId();
        //设置店铺名称
        this.storeName = order.getStoreName();
        //设置订单编号
        this.orderNo = order.getSn();
        //是否包含图片
        this.haveImage = CharSequenceUtil.isNotEmpty(memberEvaluationDTO.getImages());
        //默认开启评价
        this.status = SwitchEnum.OPEN.name();
        //默认不置顶
        this.top = false;
    }
}