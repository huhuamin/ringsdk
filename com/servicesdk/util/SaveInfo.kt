package com.servicesdk.util

import com.drake.serialize.serialize.serialLazy

object SaveInfo {
    var mac: String by serialLazy("")
    var gluRegister: Boolean by serialLazy(false)
}