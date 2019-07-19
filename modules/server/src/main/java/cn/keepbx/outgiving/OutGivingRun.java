package cn.keepbx.outgiving;

import cn.hutool.core.date.DateUtil;
import cn.hutool.core.thread.ThreadUtil;
import cn.hutool.http.HttpStatus;
import cn.jiangzeyin.common.DefaultSystemLog;
import cn.jiangzeyin.common.JsonMessage;
import cn.jiangzeyin.common.spring.SpringUtil;
import cn.keepbx.jpom.common.forward.NodeForward;
import cn.keepbx.jpom.common.forward.NodeUrl;
import cn.keepbx.jpom.model.BaseEnum;
import cn.keepbx.jpom.model.data.NodeModel;
import cn.keepbx.jpom.model.data.OutGivingModel;
import cn.keepbx.jpom.model.data.OutGivingNodeProject;
import cn.keepbx.jpom.model.data.UserModel;
import cn.keepbx.jpom.service.node.NodeService;
import cn.keepbx.jpom.service.node.OutGivingServer;
import com.alibaba.fastjson.JSONObject;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;

/**
 * 分发线程
 *
 * @author bwcx_jzy
 * @date 2019/7/18
 **/
public class OutGivingRun implements Callable<OutGivingNodeProject.Status> {
    private String outGivingId;
    private OutGivingNodeProject outGivingNodeProject;
    private NodeModel nodeModel;
    private File file;
    private OutGivingModel.AfterOpt afterOpt;
    private UserModel userModel;
    private boolean unzip;


    /**
     * 开始异步执行分发任务
     *
     * @param id        分发id
     * @param file      文件
     * @param userModel 操作的用户
     */
    public static void startRun(String id,
                                File file,
                                UserModel userModel,
                                boolean unzip) throws IOException {
        OutGivingServer outGivingServer = SpringUtil.getBean(OutGivingServer.class);
        OutGivingModel item = outGivingServer.getItem(id);
        Objects.requireNonNull(item);
        OutGivingModel.AfterOpt afterOpt = BaseEnum.getEnum(OutGivingModel.AfterOpt.class, item.getAfterOpt());
        if (afterOpt == null) {
            afterOpt = OutGivingModel.AfterOpt.No;
        }
        OutGivingModel.AfterOpt finalAfterOpt = afterOpt;
        //
        List<OutGivingNodeProject> outGivingNodeProjects = item.startBefore();
        outGivingServer.updateItem(item);
        // 开启线程
        if (afterOpt == OutGivingModel.AfterOpt.Order_Restart || afterOpt == OutGivingModel.AfterOpt.Order_Must_Restart) {
            ThreadUtil.execute(() -> {
                boolean cancel = false;
                for (OutGivingNodeProject outGivingNodeProject : outGivingNodeProjects) {
                    if (cancel) {
                        updateStatus(id, outGivingNodeProject, OutGivingNodeProject.Status.Cancel, "前一个节点分发失败，取消分发");
                    } else {
                        OutGivingRun outGivingRun = new OutGivingRun(id, outGivingNodeProject, file, finalAfterOpt, userModel, unzip);
                        OutGivingNodeProject.Status status = outGivingRun.call();
                        if (status != OutGivingNodeProject.Status.Ok) {
                            if (finalAfterOpt == OutGivingModel.AfterOpt.Order_Must_Restart) {
                                // 完整重启，不再继续剩余的节点项目
                                cancel = true;
                            }
                        }
                    }
                }
            });
        } else if (afterOpt == OutGivingModel.AfterOpt.Restart || afterOpt == OutGivingModel.AfterOpt.No) {

            outGivingNodeProjects.forEach(outGivingNodeProject -> ThreadUtil.execAsync(new OutGivingRun(id, outGivingNodeProject, file, finalAfterOpt, userModel, unzip)));
        } else {
            //
            throw new RuntimeException("Not implemented");
        }
    }

    private OutGivingRun(String outGivingId,
                         OutGivingNodeProject outGivingNodeProject,
                         File file,
                         OutGivingModel.AfterOpt afterOpt,
                         UserModel userModel,
                         boolean unzip) {
        this.outGivingId = outGivingId;
        this.unzip = unzip;
        this.outGivingNodeProject = outGivingNodeProject;
        this.file = file;
        this.afterOpt = afterOpt;
        //
        NodeService nodeService = SpringUtil.getBean(NodeService.class);
        this.nodeModel = nodeService.getItem(outGivingNodeProject.getNodeId());
        //
        this.userModel = userModel;
    }

    @Override
    public OutGivingNodeProject.Status call() {
        OutGivingNodeProject.Status result;
        try {
            //
            JsonMessage jsonMessage = fileUpload(file,
                    this.outGivingNodeProject.getProjectId(),
                    unzip,
                    afterOpt != OutGivingModel.AfterOpt.No,
                    this.nodeModel, this.userModel);
            if (jsonMessage.getCode() == HttpStatus.HTTP_OK) {
                result = OutGivingNodeProject.Status.Ok;
                updateStatus(this.outGivingId, this.outGivingNodeProject, result, jsonMessage.toString());
            } else {
                result = OutGivingNodeProject.Status.Fail;
                updateStatus(this.outGivingId, this.outGivingNodeProject, result, jsonMessage.toString());
            }
        } catch (Exception e) {
            DefaultSystemLog.ERROR().error(this.outGivingNodeProject.getNodeId() + " " + this.outGivingNodeProject.getProjectId() + " " + "分发异常保存", e);
            result = OutGivingNodeProject.Status.Fail;
            updateStatus(this.outGivingId, this.outGivingNodeProject, result, e.getMessage());
        }
        return result;
    }


    public static JsonMessage fileUpload(File file, String projectId, boolean unzip, boolean restart, NodeModel nodeModel, UserModel userModel) {
        JSONObject data = new JSONObject();
        data.put("file", file);
        data.put("id", projectId);
        if (unzip) {
            data.put("type", "unzip");
        }
        // 操作
        if (restart) {
            data.put("after", "restart");
        }
        return NodeForward.request(nodeModel, NodeUrl.Manage_File_Upload, userModel, data);
    }

    /**
     * 更新状态
     */
    private static void updateStatus(String outGivingId, OutGivingNodeProject outGivingNodeProjectItem, OutGivingNodeProject.Status status, String msg) {
        synchronized (OutGivingRun.class) {
            OutGivingServer outGivingServer = SpringUtil.getBean(OutGivingServer.class);
            OutGivingModel outGivingModel;
            try {
                outGivingModel = outGivingServer.getItem(outGivingId);
            } catch (IOException e) {
                DefaultSystemLog.ERROR().error(outGivingId + " " + outGivingNodeProjectItem + " " + "获取异常", e);
                return;
            }
            List<OutGivingNodeProject> outGivingNodeProjects = outGivingModel.getOutGivingNodeProjectList();
            for (OutGivingNodeProject outGivingNodeProject : outGivingNodeProjects) {
                if (!outGivingNodeProject.getProjectId().equalsIgnoreCase(outGivingNodeProjectItem.getProjectId()) ||
                        !outGivingNodeProject.getNodeId().equalsIgnoreCase(outGivingNodeProjectItem.getNodeId())) {
                    continue;
                }
                outGivingNodeProject.setStatus(status.getCode());
                outGivingNodeProject.setResult(msg);
                outGivingNodeProject.setLastOutGivingTime(DateUtil.now());
            }
            outGivingServer.updateItem(outGivingModel);
        }
    }
}