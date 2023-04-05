package com.akto.action;

import com.akto.dao.*;
import com.akto.dao.context.Context;
import com.akto.dto.*;
import com.akto.log.LoggerMaker;
import com.akto.log.LoggerMaker.LogDb;
import com.akto.utils.cloud.serverless.aws.Lambda;
import com.akto.utils.cloud.stack.aws.AwsStack;
import com.akto.utils.cloud.stack.dto.StackState;
import com.akto.utils.platform.DashboardStackDetails;
import com.akto.utils.platform.MirroringStackDetails;
import com.amazonaws.services.lambda.model.*;
import com.mongodb.BasicDBObject;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;
import com.opensymphony.xwork2.Action;
import com.amazonaws.services.autoscaling.AmazonAutoScaling;
import com.amazonaws.services.autoscaling.AmazonAutoScalingClientBuilder;
import com.amazonaws.services.autoscaling.model.RefreshPreferences;
import com.amazonaws.services.autoscaling.model.StartInstanceRefreshRequest;
import com.amazonaws.services.autoscaling.model.StartInstanceRefreshResult;
import com.amazonaws.services.lambda.AWSLambda;
import com.amazonaws.services.lambda.AWSLambdaClientBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;

import static com.akto.dao.MCollection.ID;
import static com.mongodb.client.model.Filters.eq;

public class AccountAction extends UserAction {

    private String newAccountName;
    private int newAccountId;
    private static final LoggerMaker loggerMaker = new LoggerMaker(AccountAction.class);

    public static final int MAX_NUM_OF_LAMBDAS_TO_FETCH = 50;
    private static AmazonAutoScaling asc = AmazonAutoScalingClientBuilder.standard().build();
    @Override
    public String execute() {

        return Action.SUCCESS.toUpperCase();
    }

    private void invokeExactLambda(String functionName, AWSLambda awsLambda) {

        InvokeRequest invokeRequest = new InvokeRequest()
            .withFunctionName(functionName)
            .withPayload("{}");
        InvokeResult invokeResult = null;
        try {

            loggerMaker.infoAndAddToDb("Invoke lambda "+functionName, LogDb.DASHBOARD);
            invokeResult = awsLambda.invoke(invokeRequest);

            String resp = new String(invokeResult.getPayload().array(), StandardCharsets.UTF_8);
            loggerMaker.infoAndAddToDb("Function: " + functionName + ", response:" + resp, LogDb.DASHBOARD);
        } catch (AWSLambdaException e) {
            loggerMaker.errorAndAddToDb(String.format("Error while invoking Lambda, %s: %s", functionName, e), LogDb.DASHBOARD);
        }
    }

    private void listMatchingLambda(String functionName) {
        AWSLambda awsLambda = AWSLambdaClientBuilder.standard().build();
        try {
            ListFunctionsRequest request = new ListFunctionsRequest();
            request.setMaxItems(MAX_NUM_OF_LAMBDAS_TO_FETCH);

            boolean done = false;
            while(!done){
                ListFunctionsResult functionResult = awsLambda
                        .listFunctions(request);
                List<FunctionConfiguration> list = functionResult.getFunctions();
                loggerMaker.infoAndAddToDb(String.format("Found %s functions", list.size()), LogDb.DASHBOARD);

                for (FunctionConfiguration config: list) {
                    loggerMaker.infoAndAddToDb(String.format("Found function: %s",config.getFunctionName()), LogDb.DASHBOARD);

                    if(config.getFunctionName().contains(functionName)) {
                        loggerMaker.infoAndAddToDb(String.format("Invoking function: %s", config.getFunctionName()), LogDb.DASHBOARD);
                        invokeExactLambda(config.getFunctionName(), awsLambda);
                    }
                }

                if(functionResult.getNextMarker() == null){
                    done = true;
                }
                request = new ListFunctionsRequest();
                request.setMaxItems(MAX_NUM_OF_LAMBDAS_TO_FETCH);
                request.setMarker(functionResult.getNextMarker());
            }


        } catch (AWSLambdaException e) {
            loggerMaker.errorAndAddToDb(String.format("Error while updating Akto: %s",e), LogDb.DASHBOARD);
        }
    }

    public void asgInstanceRefresh(StartInstanceRefreshRequest refreshRequest, String stack, String asg){
        String autoScalingGroup = AwsStack.getInstance().fetchResourcePhysicalIdByLogicalId(stack, asg);
        refreshRequest.setAutoScalingGroupName(autoScalingGroup);
        StartInstanceRefreshResult result = asc.startInstanceRefresh(refreshRequest);
        loggerMaker.infoAndAddToDb(String.format("instance refresh called on %s with result %s", asg, result.toString()), LogDb.DASHBOARD);
    }

    public void lambdaInstanceRefresh(){
        String lambda;
            try {
                lambda = AwsStack.getInstance().fetchResourcePhysicalIdByLogicalId(MirroringStackDetails.getStackName(), MirroringStackDetails.AKTO_CONTEXT_ANALYZER_UPDATE_LAMBDA);
                Lambda.getInstance().invokeFunction(lambda);
                loggerMaker.infoAndAddToDb("Successfully invoked lambda " + lambda, LogDb.DASHBOARD);
            } catch (Exception e) {
                loggerMaker.errorAndAddToDb("Failed to update Akto Context Analyzer" + e, LogDb.DASHBOARD);
            }
            try{
                lambda = AwsStack.getInstance().fetchResourcePhysicalIdByLogicalId(MirroringStackDetails.getStackName(),MirroringStackDetails.AKTO_DASHBOARD_UPDATE_LAMBDA);
                Lambda.getInstance().invokeFunction(lambda);
                loggerMaker.infoAndAddToDb("Successfully invoked lambda " +lambda, LogDb.DASHBOARD);
            } catch (Exception e) {
                loggerMaker.errorAndAddToDb("Failed to update Akto Dashboard" + e, LogDb.DASHBOARD);
            }

            try{
                lambda = AwsStack.getInstance().fetchResourcePhysicalIdByLogicalId(MirroringStackDetails.getStackName(), MirroringStackDetails.AKTO_RUNTIME_UPDATE_LAMBDA);
                Lambda.getInstance().invokeFunction(lambda);
                loggerMaker.infoAndAddToDb("Successfully invoked lambda " + lambda, LogDb.DASHBOARD);
            } catch (Exception e) {
                loggerMaker.errorAndAddToDb("Failed to update Akto Traffic Mirroring Instance" + e, LogDb.DASHBOARD);
            }
    }

    public String takeUpdate() {
        if(checkIfStairwayInstallation()) {
            RefreshPreferences refreshPreferences = new RefreshPreferences();
            StartInstanceRefreshRequest refreshRequest = new StartInstanceRefreshRequest();
            refreshPreferences.setMinHealthyPercentage(0);
            refreshPreferences.setInstanceWarmup(200);
            refreshRequest.setPreferences(refreshPreferences);
            try {
                asgInstanceRefresh(refreshRequest, MirroringStackDetails.getStackName(), MirroringStackDetails.AKTO_CONTEXT_ANALYSER_AUTO_SCALING_GROUP);
                asgInstanceRefresh(refreshRequest, MirroringStackDetails.getStackName(), MirroringStackDetails.AKTO_TRAFFIC_MIRRORING_AUTO_SCALING_GROUP);
                asgInstanceRefresh(refreshRequest, DashboardStackDetails.getStackName(), DashboardStackDetails.AKTO_DASHBOARD_AUTO_SCALING_GROUP);
            } catch (Exception e){
                loggerMaker.infoAndAddToDb("could not invoke instance refresh directly, using lambdas " + e.getMessage(), LogDb.DASHBOARD);
                lambdaInstanceRefresh();
            }
        } else {
            loggerMaker.infoAndAddToDb("This is an old installation, updating via old way", LogDb.DASHBOARD);
            listMatchingLambda("InstanceRefresh");
        }
        
        return Action.SUCCESS.toUpperCase();
    }

    private boolean checkIfStairwayInstallation() {
        StackState stackStatus = AwsStack.getInstance().fetchStackStatus(MirroringStackDetails.getStackName());
        return "CREATE_COMPLETE".equalsIgnoreCase(stackStatus.getStatus());
    }

    public static int createAccountRecord(String accountName) {

        if (accountName == null || accountName.isEmpty()) {
            throw new IllegalArgumentException("Account name can't be empty");
        }

        int triesAllowed = 10;
        int now = Context.now();
        while(triesAllowed >=0) {
            try {
                now = now - 40000;
                triesAllowed --;
                AccountsDao.instance.insertOne(new Account(now, accountName));
                return now;
            } catch (Exception e) {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e1) {
                    // eat it
                }
            }
        }

        throw new IllegalStateException("Couldn't find a suitable account id. Please try after some time.");
    }

    public String createNewAccount() {
        String email = getSUser().getLogin();
        int newAccountId = createAccountRecord(newAccountName);
        User user = initializeAccount(email, newAccountId, newAccountName,true);
        getSession().put("user", user);
        getSession().put("accountId", newAccountId);
        return Action.SUCCESS.toUpperCase();
    }

    public static User initializeAccount(String email, int newAccountId,String newAccountName,  boolean isNew) {
        UsersDao.addAccount(email, newAccountId, newAccountName);
        User user = UsersDao.instance.findOne(eq(User.LOGIN, email));
        RBAC.Role role = isNew ? RBAC.Role.ADMIN : RBAC.Role.MEMBER;
        RBACDao.instance.insertOne(new RBAC(user.getId(), role, newAccountId));
        Context.accountId.set(newAccountId);
        if (isNew) intializeCollectionsForTheAccount();
        return user;
    }

    private static void intializeCollectionsForTheAccount() {
        ApiCollectionsDao.instance.insertOne(new ApiCollection(0, "Default", Context.now(), new HashSet<>(), null, 0));

        BackwardCompatibility backwardCompatibility = BackwardCompatibilityDao.instance.findOne(new BasicDBObject());
        if (backwardCompatibility == null) {
            backwardCompatibility = new BackwardCompatibility();
            BackwardCompatibilityDao.instance.insertOne(backwardCompatibility);
        }

        // backward compatibility
        if (backwardCompatibility.getDropFilterSampleData() == 0) {
            FilterSampleDataDao.instance.getMCollection().drop();
        }
        BackwardCompatibilityDao.instance.updateOne(
                Filters.eq(ID, backwardCompatibility.getId()),
                Updates.set(BackwardCompatibility.DROP_FILTER_SAMPLE_DATA, Context.now())
        );

        if (backwardCompatibility.getResetSingleTypeInfoCount() == 0) {
            SingleTypeInfoDao.instance.resetCount();
        }

        BackwardCompatibilityDao.instance.updateOne(
                Filters.eq("_id", backwardCompatibility.getId()),
                Updates.set(BackwardCompatibility.RESET_SINGLE_TYPE_INFO_COUNT, Context.now())
        );

        SingleTypeInfoDao.instance.createIndicesIfAbsent();
        SensitiveSampleDataDao.instance.createIndicesIfAbsent();
        RuntimeFilterDao.instance.initialiseFilters();
    }

    public String goToAccount() {
        if (getSUser().getAccounts().containsKey(newAccountId+"")) {
            getSession().put("accountId", newAccountId);
            Context.accountId.set(newAccountId);
            return SUCCESS.toUpperCase();
        }

        return ERROR.toUpperCase();
    }

    public String getNewAccountName() {
        return newAccountName;
    }

    public void setNewAccountName(String newAccountName) {
        this.newAccountName = newAccountName;
    }

    public int getNewAccountId() {
        return newAccountId;
    }

    public void setNewAccountId(int newAccountId) {
        this.newAccountId = newAccountId;
    }
}
