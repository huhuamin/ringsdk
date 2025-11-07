package com.servicesdk.base

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import androidx.lifecycle.ViewModelProvider
import androidx.viewbinding.ViewBinding
import java.lang.reflect.ParameterizedType

abstract class BaseActivity<VB : ViewBinding, VM : BaseViewModel> : ThemeActivity() {


    //当前Activity绑定的 ViewModel
    lateinit var mViewModel: VM
    lateinit var mViewBind: VB
    var dataBindView: View? = null

    /**
     * 初始化view
     */
    abstract fun initView(savedInstanceState: Bundle?)

    open fun onBindViewClick() {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        initViewBind()
        setContentView(dataBindView)
        mViewModel = createViewModel()
        dataBindView?.post {
            initView(savedInstanceState)
            onBindViewClick()
        }
    }


    /**
     * 创建ViewBinding
     */
    private fun initViewBind() {
        //利用反射 根据泛型得到 ViewViewBinding
        val superClass = javaClass.genericSuperclass
        val aClass = (superClass as ParameterizedType).actualTypeArguments[0] as Class<*>
        val method = aClass.getDeclaredMethod("inflate", LayoutInflater::class.java)
        mViewBind = method.invoke(null, layoutInflater) as VB
        dataBindView = mViewBind.root
//        mViewBind.lifecycleOwner = this
    }


    /**
     * 创建viewModel
     */
    private fun createViewModel(): VM {
        return ViewModelProvider(this).get(getVmClazz(this))
    }

    private fun <VM> getVmClazz(obj: Any): VM {
        return (obj.javaClass.genericSuperclass as ParameterizedType).actualTypeArguments[1] as VM
    }

}