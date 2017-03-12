package com.tosit.project.session

import com.tosit.project.constants.Constants
import com.tosit.project.dao.factory.DAOFActory
import com.tosit.project.exception.TaskException
import com.tosit.project.javautils.{ParamUtils, StringUtils}
import com.tosit.project.scalautils.{AnalyzeUnits, SparkUtils}
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.{Row, SQLContext}
import org.apache.spark.{SparkConf, SparkContext}
import org.json.JSONObject


/**
  * 用户访问分析类
  * <p>
  * Created by Wanghan on 2017/3/11.
  * Copyright © Wanghan SCU. All Rights Reserved
  */
object UserVisitAnalyzeService {
    def main(args: Array[String]): Unit = {
        // spark配置文件
        val conf = new SparkConf().setAppName(Constants.SPARK_APP_NAME_SESSION).setMaster("local[2]")
        // spark上下文环境
        val sc = new SparkContext(conf)
        // SQL上下文环境
        val sqlContext = AnalyzeUnits.getSQLContext(sc)
        // 加载本地session访问日志测试数据
        SparkUtils.loadLocalTestDataToTmpTable(sc, sqlContext)
        // 创建DAO组件,DAO组件是用来操作数据库的
        //val taskDao = DAOFActory.getTaskDAO()
        // 通过任务常量名来获取任务ID
        //        val taskId = ParamUtils.getTaskIdFromArgs(args, Constants.SPARK_LOCAL_SESSION_TASKID)
        //        val task = if (taskId > 0) taskDao.findById(taskId) else null
        //        // 抛出task异常
        //        if (task == null) {
        //            throw new TaskException("Can't find task by id: " + taskId);
        //        }
        //        // 获取任务参数
        //        val taskParam = new JSONObject(task.getTaskParam)
        //
        //        val actionRdd = getActionRddByDateRange(sqlContext, taskParam)

        val param = new JSONObject("{\"startDate\":[\"2017-03-06\"],\"endDate\":[\"2017-03-06\"]}")
        val actionRddByDateRange = AnalyzeUnits.getActionRddByDateRange(sqlContext, param)
        sc.stop()
    }


    def aggregateBySession(sQLContext: SQLContext, actionRddByDateRange: RDD[Row]) = {
        // sessionidRddWithAction 形为(session_id,RDD[Row])
        val sessionIdRddWithAction = actionRddByDateRange.map(s => (s.getString(2), s)).groupByKey()
        // userIdRddWithSearchWordsAndClickCategoryIds 形为(user_id,session_id|searchWords|clickCategoryIds)
        val userIdRddWithSearchWordsAndClickCategoryIds = sessionIdRddWithAction.map(f = s => {
            val session_id: String = s._1
            // 用户ID
            var user_id: Long = 0L
            // 搜索关键字的集合
            var searchWords: String = null
            // 点击分类ID的集合
            var clickCategoryIds: String = null

            val iterator = s._2.iterator
            while (iterator.hasNext) {
                val row = iterator.next()
                user_id = row.getLong(1)
                val searchWord = row.getString(6)
                val clickCategoryId = row.getString(7)
                if (searchWord != null && !searchWords.contains(searchWord)) {
                    searchWords += (searchWord + ",")
                }
                if (clickCategoryId != null && !clickCategoryIds.contains(clickCategoryId)) {
                    clickCategoryIds += (clickCategoryId + ",")
                }
            }

            searchWords = StringUtils.trimComma(searchWords)
            clickCategoryIds = StringUtils.trimComma(clickCategoryIds)

            val userAggregateInfo = Constants.FIELD_SESSION_ID + "=" + session_id + Constants.VALUE_SEPARATOR +
                Constants.FIELD_SEARCH_KEYWORDS + "=" + searchWords + Constants.VALUE_SEPARATOR +
                Constants.FIELD_CLICK_CATEGORY_IDS + "=" + clickCategoryIds + Constants.VALUE_SEPARATOR

            (user_id, userAggregateInfo)
        })

        // userInfo形如(user_id,RDD[Row])
        val userInfo = AnalyzeUnits.getUserInfo(sQLContext)
        val userWithSessionInfoRdd = userInfo.join(userIdRddWithSearchWordsAndClickCategoryIds)

        userWithSessionInfoRdd.map(t => {
            val userInfo = t._2._2
            val userAggregateInfo = t._2._1
            val session_id = StringUtils.getFieldFromConcatString(userInfo, Constants.VALUE_SEPARATOR,
                Constants.FIELD_SESSION_ID)
            val age = userAggregateInfo.getInt(3)
            val professional = userAggregateInfo.getString(4)
            val city = userAggregateInfo.getString(5)
            val sex = userAggregateInfo.getString(6)

            val aggregateInfo = userAggregateInfo + Constants.VALUE_SEPARATOR +
                Constants.FIELD_AGE + "=" + age + Constants.VALUE_SEPARATOR +
                Constants.FIELD_PROFESSIONAL + "=" + professional + Constants.VALUE_SEPARATOR +
                Constants.FIELD_CITY + "=" + city + Constants.VALUE_SEPARATOR +
                Constants.FIELD_SEX + "=" + sex
            (session_id, aggregateInfo)
        })

    }
}
