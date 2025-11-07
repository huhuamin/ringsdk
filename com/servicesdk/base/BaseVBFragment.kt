package com.servicesdk.base

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.aLifeScope
import androidx.viewbinding.ViewBinding
import com.eiot.aizo.ble.util.BleUtil
import com.eiot.aizo.ble.util.Ktx
import com.eiot.aizo.ble.ext.onErrorReturn
import java.lang.reflect.ParameterizedType

abstract class BaseVBFragment<DB : ViewBinding> : BaseFragment() {

    lateinit var mViewBind: DB

    var rootView: View? = null
        private set
    var isShow = false
        private set
    var dataBindView: View? = null

    private var first = true
    open val myActivity by lazy {
        (requireActivity() as? BaseActivity<*, *>)!!
    }

    /**
     * 初始化view操作
     */
    abstract fun initView(savedInstanceState: Bundle?)

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        /**
         * rootview不能设置，container千万别使用，天坑
         */
        super.onCreateView(inflater, container, savedInstanceState)

        initDataBind()
        rootView = dataBindView

        isShow = false
        return rootView
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initView(savedInstanceState)
        onBindViewClick()
    }

    open fun lazyInit() {}//懒加载

    open fun onBindViewClick() {}

    /**
     * 创建DataBinding
     */
    private fun initDataBind() {
        //利用反射 根据泛型得到 ViewDataBinding
        val superClass = javaClass.genericSuperclass
        val aClass = (superClass as ParameterizedType).actualTypeArguments[0] as Class<*>
        val method = aClass.getDeclaredMethod("inflate", LayoutInflater::class.java)
        mViewBind = method.invoke(null, layoutInflater) as DB
        //如果重新加载，需要清空之前的view，不然会报错
        (dataBindView?.parent as? ViewGroup)?.removeView(dataBindView)
        dataBindView = mViewBind.root
    }

    override fun onStart() {
        super.onStart()
        isShow = true
    }

    override fun onResume() {
        super.onResume()
        if (first) {
            lazyInit()
            first = false
        }
        setImmersionBar()
    }

    private fun setImmersionBar() {
        if (setImmersionBarEnabled()) {
            initImmersionBar()
        }
    }

    open fun initImmersionBar() {

    }

    open fun setImmersionBarEnabled(): Boolean {
        return false
    }


    override fun onStop() {
        super.onStop()
        isShow = false
    }

    override fun onDestroyView() {
        super.onDestroyView()
        first = true
    }

    /**
     * 确保在主线程中运行，主要用于监听器或回调器的操作
     * */
    fun runMain(action: () -> Unit) {
        try {
            if (BleUtil.isMainThread()) {
                action.invoke()
            } else {
                aLifeScope.launch {
                    Ktx.runMain {
                        action.invoke()
                    }.onErrorReturn {
                        throw it
                    }.await()
                }
            }
        } catch (t: Throwable) {
            throw t
        }
    }
}