package com.servicesdk.view

import com.chad.library.adapter.base.BaseQuickAdapter
import com.chad.library.adapter.base.viewholder.BaseViewHolder
import com.servicesdk.demo.R

class TextAdapter : BaseQuickAdapter<String, BaseViewHolder>(R.layout.text_item) {
    override fun convert(holder: BaseViewHolder, item: String) {
        holder.setText(R.id.text_view, item)
    }
}