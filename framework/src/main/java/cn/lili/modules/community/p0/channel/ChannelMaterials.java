package cn.lili.modules.community.p0.channel;

import cn.lili.modules.community.p0.support.*;
import com.google.gson.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.function.Function;
import static cn.lili.modules.community.p0.support.Stage1Json.*;

/** Local typed material schema mapped to the official ecommerce API, with no generic JSON passthrough. */
@Component
@cn.lili.modules.community.p0.support.P0BusinessEnabled
public class ChannelMaterials {
    public static final String SCHEMA="WX_ECOMMERCE_S1_20260509";
    /** Omitted fields retain encrypted values; explicit null clears them and validation still applies. */
    public JsonObject merge(JsonObject existing,JsonObject patch){
        JsonObject result=existing.deepCopy();for(var entry:patch.entrySet()){
            if(entry.getValue().isJsonObject()&&result.has(entry.getKey())&&result.get(entry.getKey()).isJsonObject())result.add(entry.getKey(),merge(result.getAsJsonObject(entry.getKey()),entry.getValue().getAsJsonObject()));
            else result.add(entry.getKey(),entry.getValue().deepCopy());
        }
        if(result.has("contact")&&result.get("contact").isJsonObject()&&"LEGAL".equals(new Fields(result.get("contact")).text("type",1,20,false)))result.getAsJsonObject("contact").remove("identity");
        return result;
    }
    public Object editable(JsonObject value){JsonObject safe=value.deepCopy();var protectedFields=new TreeMap<String,String>();redact(safe,"",protectedFields);return map("fields",safe,"protectedFields",protectedFields);}
    private void redact(JsonElement value,String path,Map<String,String> protectedFields){
        if(value.isJsonObject()){var object=value.getAsJsonObject();for(String key:new ArrayList<>(object.keySet())){JsonElement item=object.get(key);String name=path.isEmpty()?key:path+"."+key;if(Set.of("number","phone","address").contains(key)&&!item.isJsonNull()){protectedFields.put(name,ChannelConfigService.mask(item.getAsString()));object.remove(key);}else redact(item,name,protectedFields);}}
        else if(value.isJsonArray()){int index=0;for(var item:value.getAsJsonArray())redact(item,path+"."+index++,protectedFields);}
    }
    static final Set<String> DOCUMENTS=Set.of("MAINLAND_IDCARD","OVERSEA_PASSPORT","HONGKONG","MACAO","TAIWAN","FOREIGN_RESIDENT","HONGKONG_MACAO_RESIDENT","TAIWAN_RESIDENT");
    private final Stage1Db db;private final Stage1Files files;private final Clock clock;
    public ChannelMaterials(Stage1Db db,Stage1Files files,@Qualifier("stage1Clock") Clock clock){this.db=db;this.files=files;this.clock=clock;}
    public JsonObject validate(Object value,String store,String actor,boolean complete){
        var f=new Fields(value).only("schemaVersion","authorizationBasis","licenseFileId","legalId","soleBeneficialOwner","beneficialOwners","contact","account","sales","shortName","settlementId","industryName","specialQualificationFileIds","additionFileIds","additionDescription");
        f.choice("schemaVersion",Set.of(SCHEMA),true);f.text("authorizationBasis",2,500,complete);
        var nativeStore=nativeStore(store);String type=object(nativeStore.get("profile_extra")).get("subjectType").getAsString();
        var legal=idDocument(f.child("legalId",complete),complete);var contact=new Fields(f.child("contact",complete)).only("type","name","phone","identity","authorizationFileId");
        String contactType=contact.choice("type",Set.of("LEGAL","AGENT"),complete),contactName=contact.text("name",2,100,complete),phone=contact.text("phone",5,20,complete);
        if(phone!=null)Stage1Exception.require(phone.matches("[0-9+-]{5,20}"),422,"CHANNEL_CONTACT_PHONE_INVALID");
        if(complete&&"LEGAL".equals(contactType))Stage1Exception.require(Objects.equals(contactName,legal.text("name",2,100,true)),422,"CHANNEL_CONTACT_IDENTITY_MISMATCH");
        if("AGENT".equals(contactType))idDocument(contact.child("identity",complete),complete);else if(contact.has("identity"))throw Stage1Exception.field("contact.identity","NOT_APPLICABLE");
        contact.id("authorizationFileId",false);
        var account=new Fields(f.child("account",complete)).only("type","name","number","bankCode","bankBranchId","bankName","branchRequired");
        String accountType=account.choice("type",Set.of("PUBLIC","PRIVATE"),complete),accountName=account.text("name",2,128,complete),number=account.text("number",6,128,complete);
        if(number!=null)Stage1Exception.require(number.matches("[0-9]{6,128}"),422,"CHANNEL_ACCOUNT_NUMBER_INVALID");
        account.text("bankCode",1,10,complete);String branch=account.text("bankBranchId",1,64,false),bankName=account.text("bankName",1,128,false);
        // Legacy drafts may contain branchRequired. It is never authority for omitting a branch.
        account.bool("branchRequired",false);
        if(complete&&Set.of("1099","其他银行").contains(account.text("bankCode",1,10,true)))Stage1Exception.require(branch!=null||bankName!=null,422,"CHANNEL_BANK_BRANCH_REQUIRED");
        if(complete){Stage1Exception.require(!"ENTERPRISE".equals(type)||"PUBLIC".equals(accountType),422,"CHANNEL_ACCOUNT_TYPE_INVALID");Stage1Exception.require(Objects.equals(accountName,"PUBLIC".equals(accountType)?id(nativeStore,"company_name"):legal.text("name",2,100,true)),422,"CHANNEL_ACCOUNT_HOLDER_MISMATCH");Stage1Exception.require(Objects.equals(legal.text("name",2,100,true),id(nativeStore,"legal_name")),422,"CHANNEL_LEGAL_PERSON_MISMATCH");}
        var sales=new Fields(f.child("sales",complete)).only("name","url","qrFileId","merchantMiniAppId");sales.text("name",1,256,complete);String url=sales.text("url",1,1024,false),qr=sales.id("qrFileId",false);sales.text("merchantMiniAppId",1,256,false);
        if(complete)Stage1Exception.require(url!=null||qr!=null,422,"CHANNEL_SALES_PROOF_REQUIRED");if(url!=null)try{var parsed=URI.create(url);Stage1Exception.require(Set.of("http","https").contains(parsed.getScheme())&&parsed.getHost()!=null&&parsed.getRawUserInfo()==null&&parsed.getRawFragment()==null&&!url.contains("\\"),422,"CHANNEL_SALES_URL_INVALID");}catch(IllegalArgumentException invalid){throw Stage1Exception.field("sales.url","INVALID_URL");}
        String shortName=f.text("shortName",1,64,complete);if(shortName!=null)Stage1Exception.require(shortName.getBytes(StandardCharsets.UTF_8).length<=64,422,"CHANNEL_SHORT_NAME_BYTES");
        // Explicit approved settlement/industry values avoid silently accepting a channel's default financial rate.
        f.integer("settlementId",1,Integer.MAX_VALUE,complete);f.text("industryName",1,200,complete);f.text("additionDescription",0,512,false);
        f.ids("specialQualificationFileIds",0,5,false);f.ids("additionFileIds",0,15,false);
        if("ENTERPRISE".equals(type)&&complete)Stage1Exception.require(f.has("soleBeneficialOwner"),422,"CHANNEL_BENEFICIAL_OWNER_REQUIRED");
        boolean sole=f.bool("soleBeneficialOwner",false);var beneficiaries=f.list("beneficialOwners",complete&&"ENTERPRISE".equals(type)&&!sole?1:0,4,complete&&"ENTERPRISE".equals(type)&&!sole);
        Stage1Exception.require(!sole||beneficiaries.isEmpty(),422,"CHANNEL_BENEFICIARY_CONFLICT");for(var beneficiary:beneficiaries)idDocument(object(beneficiary),complete);
        f.id("licenseFileId",complete);
        for(String file:references(f.json())){
            var row=db.required("SELECT purpose,store_id FROM li_p0_file_control WHERE file_id=?",file);String purpose=id(row,"purpose");Stage1Exception.require(Set.of("QUALIFICATION","CHANNEL").contains(purpose),422,"CHANNEL_FILE_PURPOSE_INVALID");files.validate(List.of(file),purpose,actor,store);
        }
        if(complete){String file=f.id("licenseFileId",true);Stage1Exception.require(db.count("SELECT COUNT(*) FROM li_p0_qualification WHERE store_id=? AND type='BUSINESS_LICENSE' AND status='APPROVED' AND approved_at<=? AND valid_from<=? AND (valid_to IS NULL OR valid_to>?) AND revoked_at IS NULL AND (superseded_at IS NULL OR superseded_at>?) AND JSON_CONTAINS(attachment_refs,JSON_QUOTE(?))",store,clock.instant(),clock.instant(),clock.instant(),clock.instant(),file)>0,422,"CHANNEL_APPROVED_LICENSE_REQUIRED");}
        return f.json();
    }
    private Fields idDocument(Object value,boolean complete){
        var f=new Fields(value).only("type","name","number","address","frontFileId","backFileId","validFrom","validThrough","longTerm");String type=f.choice("type",DOCUMENTS,complete),number=f.text("number",4,32,complete);
        f.text("name",2,100,complete);f.text("address",1,512,false);f.id("frontFileId",complete);f.id("backFileId",complete&&type!=null&&!type.equals("OVERSEA_PASSPORT"));
        if(number!=null&&type!=null){String pattern=switch(type){case "MAINLAND_IDCARD","HONGKONG_MACAO_RESIDENT","TAIWAN_RESIDENT"->"[0-9]{17}[0-9Xx]";case "HONGKONG"->"[Hh][A-Za-z0-9]{8}(?:[A-Za-z0-9]{2})?";case "MACAO"->"[Mm][A-Za-z0-9]{8}(?:[A-Za-z0-9]{2})?";case "TAIWAN"->"(?:[0-9]{8}|[0-9]{10})";case "FOREIGN_RESIDENT"->"[A-Za-z0-9]{15}";default->"[A-Za-z0-9-]{4,15}";};Stage1Exception.require(number.matches(pattern),422,"CHANNEL_ID_FORMAT_INVALID");}
        LocalDate from=f.date("validFrom",complete),through=f.date("validThrough",complete&&!f.bool("longTerm",false));if(f.bool("longTerm",false)&&through!=null)throw Stage1Exception.field("validThrough","LONG_TERM_CONFLICT");
        if(from!=null){Stage1Exception.require(!from.isBefore(LocalDate.of(1900,1,1))&&from.isBefore(LocalDate.now(clock.withZone(Stage1Rules.ZONE))),422,"CHANNEL_ID_START_INVALID");if(through!=null)Stage1Exception.require(through.isAfter(from)&&!through.isBefore(LocalDate.now(clock.withZone(Stage1Rules.ZONE))),422,"CHANNEL_ID_EXPIRED");}return f;
    }
    public Set<String> references(JsonObject materials){Set<String> result=new TreeSet<>();collect(materials,result);return Set.copyOf(result);}
    private void collect(JsonElement element,Set<String> result){if(element.isJsonObject())for(var entry:element.getAsJsonObject().entrySet()){if(entry.getValue().isJsonNull())continue;if(entry.getKey().endsWith("FileId"))result.add(new Fields(map("id",entry.getValue())).id("id",true));else if(entry.getKey().endsWith("FileIds"))for(var item:entry.getValue().getAsJsonArray())result.add(new Fields(map("id",item)).id("id",true));else collect(entry.getValue(),result);}else if(element.isJsonArray())element.getAsJsonArray().forEach(e->collect(e,result));}
    public void bind(JsonObject materials,String store,String actor,String application){for(String file:references(materials)){String purpose=id(db.required("SELECT purpose FROM li_p0_file_control WHERE file_id=?",file),"purpose");files.bind(List.of(file),purpose,actor,store,application);}}
    public Map<String,Object> safeSummary(JsonObject materials){var f=new Fields(materials);var account=new Fields(f.child("account",false));var contact=new Fields(f.child("contact",false));return map("schemaVersion",SCHEMA,"groupsProvided",materials.keySet().stream().filter(k->Set.of("legalId","contact","account","sales").contains(k)).sorted().toList(),"accountMasked",ChannelConfigService.mask(account.text("number",6,128,false)),"contactMasked",ChannelConfigService.mask(contact.text("phone",5,20,false)),"fileCount",references(materials).size(),"authorizationRecorded",f.has("authorizationBasis"));}
    public JsonObject mapToChannel(JsonObject materials,String store,Function<String,String> media){
        var f=new Fields(materials);var nativeStore=nativeStore(store);String type=object(nativeStore.get("profile_extra")).get("subjectType").getAsString();
        var license=db.required("SELECT details FROM li_p0_qualification WHERE store_id=? AND type='BUSINESS_LICENSE' AND status='APPROVED' AND JSON_CONTAINS(attachment_refs,JSON_QUOTE(?)) ORDER BY revision DESC LIMIT 1",store,f.id("licenseFileId",true));var certificate=new Fields(license.get("details"));
        var result=object(map("organization_type",type.equals("ENTERPRISE")?"2":"4","business_license_info",map("business_license_copy",media.apply(f.id("licenseFileId",true)),"business_license_number",nativeStore.get("license_num"),"merchant_name",nativeStore.get("company_name"),"legal_person",nativeStore.get("legal_name"),"company_address",nativeStore.get("company_address"),"business_time",write(List.of(certificate.date("validFrom",true).toString(),certificate.bool("longTerm",false)?"长期":certificate.date("validThrough",true).toString()))),"merchant_shortname",f.text("shortName",1,64,true)));
        var legal=new Fields(f.child("legalId",true));String docType=legal.choice("type",DOCUMENTS,true);result.addProperty("id_doc_type","IDENTIFICATION_TYPE_"+docType);
        boolean mainland=docType.equals("MAINLAND_IDCARD");var document=map(mainland?"id_card_name":"id_doc_name",legal.text("name",2,100,true),mainland?"id_card_number":"id_doc_number",legal.text("number",4,32,true),mainland?"id_card_copy":"id_doc_copy",media.apply(legal.id("frontFileId",true)),mainland?"id_card_valid_time_begin":"doc_period_begin",legal.date("validFrom",true).toString(),mainland?"id_card_valid_time":"doc_period_end",expiry(legal));
        if(legal.has("backFileId"))document.put(mainland?"id_card_national":"id_doc_copy_back",media.apply(legal.id("backFileId",true)));result.add(mainland?"id_card_info":"id_doc_info",object(document));
        var account=new Fields(f.child("account",true));var bank=map("bank_account_type",account.text("type",1,20,true).equals("PUBLIC")?"74":"75","account_name",account.text("name",2,128,true),"account_number",account.text("number",6,128,true),"account_bank",account.text("bankCode",1,10,true));optional(bank,"bank_branch_id",account.text("bankBranchId",1,64,false));optional(bank,"bank_name",account.text("bankName",1,128,false));result.add("account_info",object(bank));
        var contact=new Fields(f.child("contact",true));var admin=map("contact_type",contact.text("type",1,20,true).equals("LEGAL")?"65":"66","contact_name",contact.text("name",2,100,true),"mobile_phone",contact.text("phone",5,20,true));
        if("AGENT".equals(contact.text("type",1,20,true))){var identity=new Fields(contact.child("identity",true));admin.putAll(map("contact_id_doc_type","IDENTIFICATION_TYPE_"+identity.text("type",1,64,true),"contact_id_card_number",identity.text("number",4,32,true),"contact_id_doc_copy",media.apply(identity.id("frontFileId",true)),"contact_id_doc_period_begin",identity.date("validFrom",true).toString(),"contact_id_doc_period_end",expiry(identity)));if(identity.has("backFileId"))admin.put("contact_id_doc_copy_back",media.apply(identity.id("backFileId",true)));}
        result.add("contact_info",object(admin));var sales=new Fields(f.child("sales",true));var scene=map("store_name",sales.text("name",1,256,true));optional(scene,"store_url",sales.text("url",1,1024,false));if(sales.has("qrFileId"))scene.put("store_qr_code",media.apply(sales.id("qrFileId",true)));optional(scene,"mini_program_sub_appid",sales.text("merchantMiniAppId",1,256,false));result.add("sales_scene_info",object(scene));
        result.add("settlement_info",object(map("settlement_id",f.integer("settlementId",1,Integer.MAX_VALUE,true),"qualification_type",f.text("industryName",1,200,true))));
        if(f.has("specialQualificationFileIds"))result.addProperty("qualifications",write(f.ids("specialQualificationFileIds",0,5,false).stream().map(media).toList()));if(f.has("additionFileIds"))result.addProperty("business_addition_pics",write(f.ids("additionFileIds",0,15,false).stream().map(media).toList()));if(f.has("additionDescription"))result.addProperty("business_addition_desc",f.text("additionDescription",0,512,false));
        var owners=new JsonArray();for(var item:f.list("beneficialOwners",0,4,false)){var owner=new Fields(item);var ubo=map("ubo_id_doc_type","IDENTIFICATION_TYPE_"+owner.text("type",1,64,true),"ubo_id_doc_name",owner.text("name",2,100,true),"ubo_id_doc_number",owner.text("number",4,32,true),"ubo_id_doc_copy",media.apply(owner.id("frontFileId",true)),"ubo_id_doc_period_begin",owner.date("validFrom",true).toString(),"ubo_id_doc_period_end",expiry(owner));optional(ubo,"ubo_id_doc_address",owner.text("address",1,512,false));if(owner.has("backFileId"))ubo.put("ubo_id_doc_copy_back",media.apply(owner.id("backFileId",true)));owners.add(object(ubo));}if(!owners.isEmpty())result.add("ubo_info_list",owners);return result;
    }
    private Map<String,Object> nativeStore(String store){return db.required("SELECT d.*,c.profile_extra FROM li_store_detail d JOIN li_p0_store_control c ON c.store_id=d.store_id WHERE d.store_id=? AND c.admission_state='APPROVED'",store);}
    private static String expiry(Fields f){return f.bool("longTerm",false)?"长期":f.date("validThrough",true).toString();}
    private static void optional(Map<String,Object> map,String key,Object value){if(value!=null)map.put(key,value);}
}
