package com.servicesdk.util

import com.eiot.ringsdk.Constant
import com.eiot.ringsdk.be.currentMac
import com.eiot.ringsdk.bean.EmotionModel
import com.eiot.ringsdk.ext.logBx
import com.eiot.ringsdk.util.UiUtil
import com.jiaqiao.product.ext.compliance
import com.jiaqiao.product.ext.resString
import com.jiaqiao.product.ext.toRoundingInt
import kotlin.math.abs
import kotlin.math.roundToInt


object MoodAlgorithm {

    //UPMOOD压力等级
    const val UPMOOD_STRESS_LEVEL_INVALID = -1     //无效压力等级
    const val UPMOOD_STRESS_LEVEL_1 = 1            //压力等级，放松Low
    const val UPMOOD_STRESS_LEVEL_2 = 2            //压力等级，较低Mild
    const val UPMOOD_STRESS_LEVEL_3 = 3            //压力等级，中等Moderate
    const val UPMOOD_STRESS_LEVEL_4 = 4            //压力等级，偏高High
    const val UPMOOD_STRESS_LEVEL_5 = 5            //压力等级，严重Severe

    //压力等级
    const val STRESS_LEVEL_INVALID = -1     //无效压力等级
    const val STRESS_LEVEL_1 = 1            //压力等级，放松Relax
    const val STRESS_LEVEL_2 = 2            //压力等级，正常Normal
    const val STRESS_LEVEL_3 = 3            //压力等级，中等Moderate
    const val STRESS_LEVEL_4 = 4            //压力等级，偏高High

    //疲劳等级
    const val FATIGUE_LEVEL_INVALID = -1    //无效疲劳等级
    const val FATIGUE_LEVEL_1 = 1           //疲劳等级，轻松Easy
    const val FATIGUE_LEVEL_2 = 2           //疲劳等级，平和Calm
    const val FATIGUE_LEVEL_3 = 3           //疲劳等级，热身Moderate
    const val FATIGUE_LEVEL_4 = 4           //疲劳等级，投入Engaged
    const val FATIGUE_LEVEL_5 = 5           //疲劳等级，疲劳Intese

    //情绪倾向等级
    const val VALENCE_LEVEL_INVALID = -1       //无效情绪倾向
    const val VALENCE_LEVEL_1 = 1              //正向Positive
    const val VALENCE_LEVEL_2 = 2              //中立Neutral
    const val VALENCE_LEVEL_3 = 3              //负向Negative

    //心情、表情
    const val EMOTION_TYPE_INVALID = -1         //无效心情类型
    const val EMOTION_TYPE_ANXIOUS = 1          //焦虑 1
    const val EMOTION_TYPE_SAD = 2              //悲伤 2
    const val EMOTION_TYPE_UNPLEASANT = 3       //不舒服 6
    const val EMOTION_TYPE_TENSE = 4            //紧张 3
    const val EMOTION_TYPE_CHALLENGED = 5       //挑战 4
    const val EMOTION_TYPE_CONFUSED = 6         //困惑 5
    const val EMOTION_TYPE_CALM = 7             //冷静 6
    const val EMOTION_TYPE_ZEN = 8              //禅 9
    const val EMOTION_TYPE_PLEASANT = 9         //愉悦 8
    const val EMOTION_TYPE_HAPPY = 10           //开心 10
    const val EMOTION_TYPE_EXCITMENT = 11       //兴奋 11


    //情绪倾向划分
    const val VALENCE_INVALID = -11.0f           //无效情绪倾向数据
    const val VALENCE_VALID_MIN = -5.0f          //情绪倾向最小值
    const val VALENCE_LEVEL_DIVIDE_1 = -1.0f     //小于该值则为Level 1
    const val VALENCE_LEVEL_DIVIDE_2 = 1.0f      //小于该值则为Level 2，大于该值则为Level 2
    const val VALENCE_VALID_MAX = 5.0f           //情绪倾向最大值

    //UPMOOD压力等级划分
    const val UPMOOD_STRESS_INVALID = -1.0f             //无效压力指数数据
    const val UPMOOD_STRESS_VALID_MIN = 0.0f            //压力指数最小值
    const val UPMOOD_STRESS_LEVEL_DIVIDE_1 = 5.5f       //小于该值则为Level 1
    const val UPMOOD_STRESS_LEVEL_DIVIDE_2 = 6.4f       //小于该值则为Level 2
    const val UPMOOD_STRESS_LEVEL_DIVIDE_3 = 7.4f       //小于该值则为Level 3
    const val UPMOOD_STRESS_LEVEL_DIVIDE_4 = 8.1f       //小于该值则为Level 4，大于等于该值则为Level 5
    const val UPMOOD_STRESS_VALID_MAX = 10.0f           //压力指数最大值

    //压力等级划分
    const val STRESS_INVALID = -1                       //无效压力指数数据
    const val STRESS_VALID_MIN = 0                      //压力指数最小值
    const val STRESS_LEVEL_THRESHOLD_1 = 30             //小于该值则为Level 1
    const val STRESS_LEVEL_THRESHOLD_2 = 60             //小于该值则为Level 2
    const val STRESS_LEVEL_THRESHOLD_3 = 80             //小于该值则为Level 3，大于等于该值则为Level 4
    const val STRESS_VALID_MAX = 100                    //压力指数最大值

    //疲劳等级划分
    const val FATIGUE_INVALID = -1.0f            //无效疲劳指数数据
    const val FATIGUE_VALID_MIN = 0.0f           //疲劳指数最小值
    const val FATIGUE_LEVEL_DIVIDE_1 = 6.0f      //小于该值则为Level 1
    const val FATIGUE_LEVEL_DIVIDE_2 = 7.0f      //小于该值则为Level 2
    const val FATIGUE_LEVEL_DIVIDE_3 = 10.0f     //小于该值则为Level 3
    const val FATIGUE_LEVEL_DIVIDE_4 = 14.0f     //小于该值则为Level 4，大于等于该值则为Level 5
    const val FATIGUE_VALID_MAX = 15.0f          //疲劳指数最大值


    //心情权重
    private val emotionWeight = intArrayOf(-6, -5, -1, -4, -3, -2, 2, 4, 3, 5, 6)     //{焦虑1,...,兴奋11}
    //压力权重
    private val stressWeight = intArrayOf(2, 1, -1, -2, -2)       //{放松Low，...，严重Severe}
    //情绪倾向权重
    private val valenceWeight = intArrayOf(2, 1, 2)          //{负向，中立，正向}



    /**
     * 根据情绪倾向值换算情绪倾向等级
     * 返回值：Int
     **/
    fun valence2ValenceLevel(valence: Float): Int {
        var valenceLevel = VALENCE_LEVEL_INVALID

        if (valence <= VALENCE_VALID_MAX && valence > VALENCE_LEVEL_DIVIDE_2) {
            valenceLevel = VALENCE_LEVEL_1
        } else if (valence >= VALENCE_LEVEL_DIVIDE_1) {
            valenceLevel = VALENCE_LEVEL_2
        } else if (valence >= VALENCE_VALID_MIN) {
            valenceLevel = VALENCE_LEVEL_3
        } else {

        }
        return valenceLevel
    }

    /**
     * 根据Upmood压力指数计算Upmood压力等级
     * 返回值：Int
     **/
    fun stressRaw2StressLevelRaw(stress: Float): Int {
        var stressLevel = UPMOOD_STRESS_LEVEL_INVALID

        if (UPMOOD_STRESS_VALID_MIN <= stress && stress < UPMOOD_STRESS_LEVEL_DIVIDE_1) {
            stressLevel = UPMOOD_STRESS_LEVEL_1
        } else if (stress < UPMOOD_STRESS_LEVEL_DIVIDE_2) {
            stressLevel = UPMOOD_STRESS_LEVEL_2
        } else if (stress < UPMOOD_STRESS_LEVEL_DIVIDE_3) {
            stressLevel = UPMOOD_STRESS_LEVEL_3
        } else if (stress < UPMOOD_STRESS_LEVEL_DIVIDE_4) {
            stressLevel = UPMOOD_STRESS_LEVEL_4
        } else if (stress <= UPMOOD_STRESS_VALID_MAX) {
            stressLevel = UPMOOD_STRESS_LEVEL_5
        } else {

        }
        return stressLevel
    }


    /**
     * 根据压力指数计算压力等级
     * 返回值：Int
     **/
    fun stress2StressLevel(stress: Int): Int {
        var stressLevel = STRESS_LEVEL_INVALID

        when {
            stress >= STRESS_VALID_MIN && stress < STRESS_LEVEL_THRESHOLD_1 -> {
                stressLevel = STRESS_LEVEL_1
            }
            stress >= STRESS_LEVEL_THRESHOLD_1 && stress < STRESS_LEVEL_THRESHOLD_2 -> {
                stressLevel = STRESS_LEVEL_2
            }
            stress >= STRESS_LEVEL_THRESHOLD_2 && stress < STRESS_LEVEL_THRESHOLD_3 -> {
                stressLevel = STRESS_LEVEL_3
            }
            stress >= STRESS_LEVEL_THRESHOLD_3 && stress < STRESS_VALID_MAX -> {
                stressLevel = STRESS_LEVEL_4
            }
        }

        return stressLevel
    }
    /**
     * 根据疲劳指数计算疲劳等级
     * 返回值：Int
     **/
    fun fatigue2FatigueLevel(fatigue: Float): Int {
        var fatigueLevel = FATIGUE_LEVEL_INVALID

        if (FATIGUE_VALID_MIN <= fatigue && fatigue < FATIGUE_LEVEL_DIVIDE_1) {
            fatigueLevel = FATIGUE_LEVEL_1
        } else if (fatigue < FATIGUE_LEVEL_DIVIDE_2) {
            fatigueLevel = FATIGUE_LEVEL_2
        } else if (fatigue < FATIGUE_LEVEL_DIVIDE_3) {
            fatigueLevel = FATIGUE_LEVEL_3
        } else if (fatigue < FATIGUE_LEVEL_DIVIDE_4) {
            fatigueLevel = FATIGUE_LEVEL_4
        } else if (fatigue <= FATIGUE_VALID_MAX) {
            fatigueLevel = FATIGUE_LEVEL_5
        } else {

        }
        return fatigueLevel
    }
    /**
     * 根据心情标签文本转换成心情等级
     * 返回值：Int
     **/
    fun emotionLabel2EmotionLevel(emotionLabel: String): Int {
        return when (emotionLabel.toLowerCase()) {
            "anxious" -> EMOTION_TYPE_ANXIOUS
            "sad" -> EMOTION_TYPE_SAD
            "unpleasant" -> EMOTION_TYPE_UNPLEASANT
            "tense" -> EMOTION_TYPE_TENSE
            "challenged" -> EMOTION_TYPE_CHALLENGED
            "confused" -> EMOTION_TYPE_CONFUSED
            "calm" -> EMOTION_TYPE_CALM
            "zen" -> EMOTION_TYPE_ZEN
            "pleasant" -> EMOTION_TYPE_PLEASANT
            "happy" -> EMOTION_TYPE_HAPPY
            "excitement" -> EMOTION_TYPE_EXCITMENT
            "loading" -> EMOTION_TYPE_CALM
            else -> EMOTION_TYPE_INVALID
        }
    }



    /**
     * 根据upmood云端返回值valenceRaw（0.0-3.0）换算为自己的情绪倾向valence（-5-+5）
     *   0~0.4为正向，0对应5，0.4对应1
     *   0.4~1.6为中立，1.6对应-1
     *   1.6~3为负向，3对应-5
     *   04和1.6算中立
     * 返回值：Float
     **/
    fun UpmoodValenceMap(valenceRaw: Float): Float {
        var valence = 0.0f
        when {
            valenceRaw >= 3 -> {
                valence = -5.0f
            }
            valenceRaw > 1.6f && valenceRaw < 3 -> {
                valence = -1f - (valenceRaw - 1.6f) * (5f - 1f) / (3f - 1.6f)
                valence = UiUtil.getFloatKeepStr(valence, 1).toFloat()
                if (valence > -1.1f)
                    valence = -1.1f
                if (valence < -5.0f)
                    valence = -5.0f
            }
            valenceRaw >= 0.4 && valenceRaw <= 1.6f -> {
                valence = 1f - (valenceRaw - 0.4f) * (1f - (-1f)) / (1.6f - 0.4f)
                valence = UiUtil.getFloatKeepStr(valence, 1).toFloat()
                if (valence < -1.0f)
                    valence = -1.0f
                if (valence > 1.0f)
                    valence = 1.0f
            }
            valenceRaw >= 0.0f && valenceRaw <= 1.6f -> {
                valence = 5f - (valenceRaw - 0.0f) * (5f - 1f) / (0.4f - 0.0f)
                valence = UiUtil.getFloatKeepStr(valence, 1).toFloat()
                if (valence < 1.1f)
                    valence = 1.1f
                if (valence > 5)
                    valence = 5.0f
            }
            valenceRaw <= 0.0f -> {
                valence = 5.0f
            }
        }

        return valence
    }

    /**
     * 根据upmood云端返回的压力stress（0.0-10.0）转换为自己的压力指数（0-100）
     * 返回值：Int
     **/
    fun UpmoodStressMap(stressRaw: Float): Int {
        var stress = Constant.INVALID_STRESS

        when {
            stressRaw <= UPMOOD_STRESS_VALID_MIN -> {
                stress = 0
            }
            stressRaw > UPMOOD_STRESS_VALID_MIN && stressRaw < UPMOOD_STRESS_LEVEL_DIVIDE_1 -> {
                stress = (0 + ((stressRaw - 0f) * (29f - 0f) / (5.5f - 0f))).toRoundingInt().compliance(0, 29)
            }
            stressRaw >= UPMOOD_STRESS_LEVEL_DIVIDE_1 && stressRaw < UPMOOD_STRESS_LEVEL_DIVIDE_3 -> {
                stress = (30 + ((stressRaw - 5.5f) * (59f - 30f) / (7.4f - 5.5f))).toRoundingInt().compliance(30, 59)
            }
            stressRaw >= UPMOOD_STRESS_LEVEL_DIVIDE_3 && stressRaw < UPMOOD_STRESS_LEVEL_DIVIDE_4 -> {
                stress = (60 + ((stressRaw - 7.4f) * (79f - 60f) / (8.1f - 7.4f))).toRoundingInt().compliance(60, 79)
            }
            stressRaw >= UPMOOD_STRESS_LEVEL_DIVIDE_4 && stressRaw < UPMOOD_STRESS_VALID_MAX -> {
                stress = (80 + ((stressRaw - 8.1f) * (100f - 80f) / (10.0f - 8.1f))).toRoundingInt().compliance(80, 100)
            }
            else -> {
                stress = 100
            }
        }
        //上一版压力范围为1~99
        if (stress <= 0)
            stress = 1
        if (stress >= 100)
            stress = 99

        return stress
    }

    /**
     * 根据Upmood返回的Valence和Stress计算疲劳指数Fatigue
     * 返回值：Float
     **/
    fun calcuFatigue(stressRaw: Float, valence: Float): Float {
        var fatigue = 0.0f

        when (stressRaw2StressLevelRaw(stressRaw)) {
            UPMOOD_STRESS_LEVEL_1 -> {
                when (valence2ValenceLevel(valence)) {
                    VALENCE_LEVEL_1 -> fatigue = 1.0f
                    VALENCE_LEVEL_2 -> fatigue = 3.0f
                    VALENCE_LEVEL_3 -> fatigue = 5.0f
                }
            }
            UPMOOD_STRESS_LEVEL_2 -> {
                when (valence2ValenceLevel(valence)) {
                    VALENCE_LEVEL_1 -> fatigue = 2.0f
                    VALENCE_LEVEL_2 -> fatigue = 4.0f
                    VALENCE_LEVEL_3 -> fatigue = 6.0f
                }
            }
            UPMOOD_STRESS_LEVEL_3 -> {
                when (valence2ValenceLevel(valence)) {
                    VALENCE_LEVEL_1 -> fatigue = 7.0f
                    VALENCE_LEVEL_2 -> fatigue = 8.0f
                    VALENCE_LEVEL_3 -> fatigue = 9.0f
                }
            }
            UPMOOD_STRESS_LEVEL_4 -> {
                when (valence2ValenceLevel(valence)) {
                    VALENCE_LEVEL_1 -> fatigue = 10.0f
                    VALENCE_LEVEL_2 -> fatigue = 11.0f
                    VALENCE_LEVEL_3 -> fatigue = 12.0f
                }
            }
            UPMOOD_STRESS_LEVEL_5 -> {
                when (valence2ValenceLevel(valence)) {
                    VALENCE_LEVEL_1 -> fatigue = 13.0f
                    VALENCE_LEVEL_2 -> fatigue = 14.0f
                    VALENCE_LEVEL_3 -> fatigue = 15.0f
                }
            }
            else -> {
            }
        }

        return fatigue
    }


    /**
     * 计算一天的平均心情
     * 返回值：Int
     **/
    fun calcuDailyEmotionLevel(moodDataList: MutableList<EmotionModel>, lastMood: EmotionModel): Int {

        if (moodDataList == null || lastMood == null || moodDataList.size <= 0) {
            return 0
        }

        var emotionCount = intArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)

        //计算一天平均心情Emotion
        var divisor = 0
        for (i in 0 until 11) {
            divisor = divisor + emotionWeight[i] * emotionCount[i]
        }
        var avgEmotion = (divisor.toFloat()/moodDataList.size.toFloat()).roundToInt()
        var dailyEmotionLevel = 0
        if (abs(avgEmotion) != 0) {
            when (avgEmotion) {
                6 -> dailyEmotionLevel = EMOTION_TYPE_EXCITMENT
                5 -> dailyEmotionLevel = EMOTION_TYPE_HAPPY
                4 -> dailyEmotionLevel = EMOTION_TYPE_CALM
                3 -> dailyEmotionLevel = EMOTION_TYPE_PLEASANT
                2 -> dailyEmotionLevel = EMOTION_TYPE_CALM
                1 -> dailyEmotionLevel = EMOTION_TYPE_CALM
                -1 -> dailyEmotionLevel = EMOTION_TYPE_UNPLEASANT
                -2 -> dailyEmotionLevel = EMOTION_TYPE_CONFUSED
                -3 -> dailyEmotionLevel = EMOTION_TYPE_CHALLENGED
                -4 -> dailyEmotionLevel = EMOTION_TYPE_TENSE
                -5 -> dailyEmotionLevel = EMOTION_TYPE_SAD
                -6 -> dailyEmotionLevel = EMOTION_TYPE_ANXIOUS
                else -> {}
            }
        } else {
            if (lastMood != null) {
                if (lastMood.emotionLevel == EMOTION_TYPE_EXCITMENT
                    || lastMood.emotionLevel == EMOTION_TYPE_HAPPY
                    || lastMood.emotionLevel == EMOTION_TYPE_ZEN
                    || lastMood.emotionLevel == EMOTION_TYPE_PLEASANT
                    || lastMood.emotionLevel == EMOTION_TYPE_CALM
                ) {
                    dailyEmotionLevel = EMOTION_TYPE_CALM
                } else {
                    dailyEmotionLevel = EMOTION_TYPE_UNPLEASANT
                }
            } else {
                dailyEmotionLevel = EMOTION_TYPE_CALM
            }
        }

        return dailyEmotionLevel
    }


    /**
     * 计算一天的平均情绪倾向
     * 返回值：Float
     **/
    fun calcuDailyValence(moodDataList: MutableList<EmotionModel>): Float {

        if (moodDataList == null || moodDataList.size <= 0) {
            return VALENCE_INVALID
        }

        var valenceSum = 0.0f
        var valenceNum = 0
        moodDataList.forEach {
            valenceSum = valenceSum + it.valence
            valenceNum = valenceNum + 1
            //情绪倾向加权求和
//            when {
//                it.valence >= VALENCE_LEVEL_DIVIDE_1 && it.valence <= VALENCE_LEVEL_DIVIDE_2 -> {
//                    valenceSum = valenceSum + it.valence * valenceWeight[1]
//                }
//                it.valence > VALENCE_LEVEL_DIVIDE_2 && it.valence <= VALENCE_VALID_MAX -> {
//                    valenceSum = valenceSum + it.valence * valenceWeight[2]
//                }
//                it.valence < VALENCE_LEVEL_DIVIDE_1 && it.valence >= VALENCE_VALID_MIN -> {
//                    valenceSum = valenceSum + it.valence * valenceWeight[0]
//                }
//            }
        }

        //计算一天平均情绪倾向
        var dailyValence = valenceSum / valenceNum.toFloat()
        //dailyValence = BigDecimal(dailyValence.toDouble()).setScale(2, RoundingMode.HALF_UP).toFloat()
        if (dailyValence < VALENCE_VALID_MIN)
            dailyValence = VALENCE_VALID_MIN
        if (dailyValence > VALENCE_VALID_MAX)
            dailyValence = VALENCE_VALID_MAX
        logBx("计算一天平均情绪倾向, 测量次数=${moodDataList.size}, 平均情绪倾向=${dailyValence}")
        return dailyValence
    }

    /**
     * 计算一天平均压力指数
     * 返回值：Int，小于0则为无效值
     **/
    fun calcuDailyStress(moodDataList: MutableList<EmotionModel>): Int {

        if (moodDataList == null || moodDataList.size <= 0) {
            return Constant.INVALID_STRESS
        }

        var stressSum = 0.0f
        moodDataList.forEach {
            stressSum = stressSum + it.stress.toFloat()
        }
        val dailyStress = (stressSum / moodDataList.size.toFloat()).toInt().compliance(Constant.MIN_STRESS, Constant.MAX_STRESS)
        logBx("计算一天平均压力指数, 测量次数=${moodDataList.size}, 平均压力指数=${dailyStress}")
        return dailyStress
    }


    /**
     * 计算一天平均疲劳指数
     * 返回值：Float
     **/
    fun calcuDailyFatigue(moodDataList: MutableList<EmotionModel>): Float {

        if (moodDataList == null || moodDataList.size <= 0) {
            return FATIGUE_INVALID
        }

        var rpeSum = 0.0f
        moodDataList.forEach {
            rpeSum = rpeSum + it.rpe
        }
        var dailyRpe = rpeSum / moodDataList.size.toFloat()
        if (dailyRpe < FATIGUE_VALID_MIN)
            dailyRpe = FATIGUE_VALID_MIN
        if (dailyRpe > FATIGUE_VALID_MAX)
            dailyRpe = FATIGUE_VALID_MAX
        logBx("计算一天平均疲劳指数, 测量次数=${moodDataList.size}, 平均疲劳指数=${dailyRpe}")
        return dailyRpe
    }

}