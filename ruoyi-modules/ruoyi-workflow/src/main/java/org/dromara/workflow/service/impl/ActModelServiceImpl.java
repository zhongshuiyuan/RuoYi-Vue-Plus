package org.dromara.workflow.service.impl;

import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.io.IoUtil;
import cn.hutool.core.util.ArrayUtil;
import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.core.util.ZipUtil;
import cn.hutool.json.JSONUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.json.utils.JsonUtils;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.workflow.domain.bo.ModelBo;
import org.dromara.workflow.service.IActModelService;
import org.dromara.workflow.utils.WorkflowUtils;
import org.flowable.bpmn.converter.BpmnXMLConverter;
import org.flowable.bpmn.model.BpmnModel;
import org.flowable.editor.constants.ModelDataJsonConstants;
import org.flowable.editor.language.json.converter.BpmnJsonConverter;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.repository.Deployment;
import org.flowable.engine.repository.Model;
import org.flowable.engine.repository.ModelQuery;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.MultiValueMap;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.dromara.workflow.common.constant.FlowConstant.NAMESPACE;
import static org.flowable.editor.constants.ModelDataJsonConstants.*;

/**
 * 模型管理 服务层实现
 *
 * @author may
 */
@RequiredArgsConstructor
@Service
public class ActModelServiceImpl implements IActModelService {

    private final RepositoryService repositoryService;

    /**
     * 分页查询模型
     *
     * @param modelBo 模型参数
     * @return 返回分页列表
     */
    @Override
    public TableDataInfo<Model> getByPage(ModelBo modelBo) {
        ModelQuery query = repositoryService.createModelQuery();
        query.modelTenantId(LoginHelper.getTenantId());
        if (StringUtils.isNotEmpty(modelBo.getName())) {
            query.modelNameLike("%" + modelBo.getName() + "%");
        }
        if (StringUtils.isNotEmpty(modelBo.getKey())) {
            query.modelKey(modelBo.getKey());
        }
        query.orderByLastUpdateTime().desc();
        //创建时间降序排列
        query.orderByCreateTime().desc();
        // 分页查询
        List<Model> modelList = query.listPage(modelBo.getPageNum(), modelBo.getPageSize());
        if (CollectionUtil.isNotEmpty(modelList)) {
            modelList.forEach(e -> {
                boolean isNull = JSONUtil.isNull(JSONUtil.parseObj(e.getMetaInfo()).get(ModelDataJsonConstants.MODEL_DESCRIPTION));
                if (!isNull) {
                    e.setMetaInfo((String) JSONUtil.parseObj(e.getMetaInfo()).get(ModelDataJsonConstants.MODEL_DESCRIPTION));
                } else {
                    e.setMetaInfo(StrUtil.EMPTY);
                }
            });
        }
        // 总记录数
        long total = query.count();
        return new TableDataInfo<>(modelList, total);
    }

    /**
     * 新增模型
     *
     * @param modelBo 模型请求对象
     * @return 结果
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean saveNewModel(ModelBo modelBo) {
        try {
            int version = 0;
            String key = modelBo.getKey();
            String name = modelBo.getName();
            String description = modelBo.getDescription();
            Model checkModel = repositoryService.createModelQuery().modelKey(key).modelTenantId(LoginHelper.getTenantId()).singleResult();
            if (ObjectUtil.isNotNull(checkModel)) {
                throw new ServiceException("模型key已存在！");
            }
            // 1. 初始空的模型
            Model model = repositoryService.newModel();
            model.setKey(key);
            model.setName(name);
            model.setVersion(version);
            model.setTenantId(LoginHelper.getTenantId());
            ObjectMapper objectMapper = JsonUtils.getObjectMapper();
            // 封装模型json对象
            ObjectNode objectNode = JsonUtils.getObjectMapper().createObjectNode();
            objectNode.put(MODEL_NAME, name);
            objectNode.put(MODEL_REVISION, version);
            objectNode.put(MODEL_DESCRIPTION, description);
            model.setMetaInfo(objectNode.toString());
            // 保存初始化的模型基本信息数据
            repositoryService.saveModel(model);
            // 封装模型对象基础数据json串
            ObjectNode editorNode = objectMapper.createObjectNode();
            ObjectNode stencilSetNode = objectMapper.createObjectNode();
            stencilSetNode.put("namespace", NAMESPACE);
            editorNode.replace("stencilset", stencilSetNode);
            // 标识key
            ObjectNode propertiesNode = objectMapper.createObjectNode();
            propertiesNode.put("process_id", key);
            propertiesNode.put("name", name);
            propertiesNode.put("description", description);
            editorNode.replace("properties", propertiesNode);
            repositoryService.addModelEditorSource(model.getId(), editorNode.toString().getBytes(StandardCharsets.UTF_8));
            return true;
        } catch (Exception e) {
            e.printStackTrace();
            throw new ServiceException(e.getMessage());
        }
    }

    /**
     * 查询模型
     *
     * @param modelId 模型id
     * @return 模型数据
     */
    @Override
    public ObjectNode getModelInfo(String modelId) {
        ObjectNode modelNode = null;
        Model model = repositoryService.createModelQuery().modelId(modelId).modelTenantId(LoginHelper.getTenantId()).singleResult();
        ObjectMapper objectMapper = JsonUtils.getObjectMapper();
        if (model != null) {
            try {
                if (StringUtils.isNotEmpty(model.getMetaInfo())) {
                    modelNode = (ObjectNode) objectMapper.readTree(model.getMetaInfo());
                } else {
                    modelNode = objectMapper.createObjectNode();
                    modelNode.put(MODEL_NAME, model.getName());
                }
                modelNode.put(MODEL_ID, model.getId());
                modelNode.put("key", model.getKey());
                Integer version = model.getVersion();
                if (version > 0) {
                    byte[] modelEditorSource = repositoryService.getModelEditorSource(model.getId());
                    ByteArrayInputStream byteArrayInputStream = IoUtil.toStream(modelEditorSource);
                    XMLInputFactory xif = XMLInputFactory.newInstance();
                    XMLStreamReader xtr = xif.createXMLStreamReader(byteArrayInputStream);
                    BpmnModel bpmnModel = new BpmnXMLConverter().convertToBpmnModel(xtr);
                    BpmnJsonConverter bpmnJsonConverter = new BpmnJsonConverter();
                    ObjectNode jsonNodes = bpmnJsonConverter.convertToJson(bpmnModel);
                    modelNode.set("model", jsonNodes);
                } else {
                    ObjectNode editorJsonNode = (ObjectNode) objectMapper.readTree(
                        new String(repositoryService.getModelEditorSource(model.getId()), StandardCharsets.UTF_8));
                    modelNode.set("model", editorJsonNode);
                }

            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        return modelNode;
    }

    /**
     * 编辑模型
     *
     * @param modelId 模型id
     * @param values  模型数据
     * @return 结果
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean editModel(String modelId, MultiValueMap<String, String> values) {
        try {
            Model model = repositoryService.createModelQuery().modelId(modelId).modelTenantId(LoginHelper.getTenantId()).singleResult();
            ObjectMapper objectMapper = JsonUtils.getObjectMapper();
            ObjectNode modelJson = (ObjectNode) objectMapper.readTree(model.getMetaInfo());

            modelJson.put(MODEL_NAME, values.getFirst("name"));
            modelJson.put(MODEL_DESCRIPTION, values.getFirst("description"));
            modelJson.put(MODEL_REVISION, model.getVersion() + 1);
            model.setMetaInfo(modelJson.toString());
            model.setName(values.getFirst("name"));
            // 每次保存把版本更新+1
            model.setVersion(model.getVersion() + 1);
            // 获取唯一标识key
            String key = values.getFirst("key");
            List<Model> list = repositoryService.createModelQuery().modelKey(key).modelTenantId(LoginHelper.getTenantId()).list();
            list.stream().filter(e -> !e.getId().equals(model.getId())).findFirst().ifPresent(e -> {
                throw new ServiceException("模型key已存在！");
            });
            model.setKey(key);
            repositoryService.saveModel(model);
            byte[] xmlBytes = WorkflowUtils.bpmnJsonToXmlBytes(Objects.requireNonNull(values.getFirst("json_xml")).getBytes(StandardCharsets.UTF_8));
            if (ArrayUtil.isEmpty(xmlBytes)) {
                throw new ServiceException("模型不能为空！");
            }
            repositoryService.addModelEditorSource(model.getId(), xmlBytes);
            return true;
        } catch (Exception e) {
            e.printStackTrace();
            throw new ServiceException(e.getMessage());
        }
    }

    /**
     * 模型部署
     *
     * @param id 模型id
     * @return 结果
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean modelDeploy(String id) {
        try {
            //查询流程定义模型xml
            byte[] xmlBytes = repositoryService.getModelEditorSource(id);
            if (ArrayUtil.isEmpty(xmlBytes)) {
                throw new ServiceException("模型数据为空，请先设计流程定义模型，再进行部署！");
            }
            byte[] toXmlBytes = WorkflowUtils.bpmnJsonToXmlBytes(xmlBytes);
            if (ArrayUtil.isEmpty(toXmlBytes)) {
                throw new ServiceException("模型不能为空，请至少设计一条主线流程！");
            }
            // 查询模型的基本信息
            Model model = repositoryService.getModel(id);
            // xml资源的名称 ，对应act_ge_bytearray表中的name_字段
            String processName = model.getName() + ".bpmn20.xml";
            //调用部署相关的api方法进行部署流程定义
            Deployment deployment = repositoryService.createDeployment()
                // 部署名称
                .name(model.getName())
                // 部署标识key
                .key(model.getKey())
                // 部署流程分类
                .category(model.getCategory())
                // bpmn20.xml资源
                .addBytes(processName, xmlBytes)
                // 租户id
                .tenantId(LoginHelper.getTenantId())
                .deploy();

            // 更新 部署id 到流程定义模型数据表中
            model.setDeploymentId(deployment.getId());
            repositoryService.saveModel(model);
            return true;
        } catch (Exception e) {
            e.printStackTrace();
            throw new ServiceException(e.getMessage());
        }
    }

    /**
     * 导出模型zip压缩包
     *
     * @param modelId  模型id
     * @param response 相应
     */
    @Override
    public void exportZip(String modelId, HttpServletResponse response) {
        ZipOutputStream zos = null;
        try {
            zos = ZipUtil.getZipOutputStream(response.getOutputStream(), StandardCharsets.UTF_8);
            // 压缩包文件名
            String zipName = "模型不存在";
            //查询模型基本信息
            Model model = repositoryService.getModel(modelId);
            byte[] xmlBytes = repositoryService.getModelEditorSource(modelId);
            if (ObjectUtil.isNotNull(model)) {
                byte[] toXmlBytes = WorkflowUtils.bpmnJsonToXmlBytes(xmlBytes);
                if (ArrayUtil.isEmpty(toXmlBytes)) {
                    zipName = "模型不能为空，请至少设计一条主线流程！";
                    zos.putNextEntry(new ZipEntry(zipName + ".txt"));
                    zos.write(zipName.getBytes(StandardCharsets.UTF_8));
                } else if (ArrayUtil.isEmpty(xmlBytes)) {
                    zipName = "模型数据为空，请先设计流程定义模型，再进行部署！";
                    zos.putNextEntry(new ZipEntry(zipName + ".txt"));
                    zos.write(zipName.getBytes(StandardCharsets.UTF_8));
                } else {
                    String fileName = model.getName() + "-" + model.getKey();
                    // 压缩包文件名
                    zipName = fileName + ".zip";
                    // 将xml添加到压缩包中(指定xml文件名：请假流程.bpmn20.xml
                    zos.putNextEntry(new ZipEntry(fileName + ".bpmn20.xml"));
                    zos.write(xmlBytes);
                }
            }
            response.setHeader("Content-Disposition",
                "attachment; filename=" + URLEncoder.encode(zipName, StandardCharsets.UTF_8) + ".zip");
            // 刷出响应流
            response.flushBuffer();
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            if (zos != null) {
                try {
                    zos.closeEntry();
                    zos.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
    }
}
