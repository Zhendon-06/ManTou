package com.hfad.mantou.data

import androidx.lifecycle.Observer
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CopyOnWriteArraySet

/**
 * 基础版可观察数据容器，类似无生命周期的 LiveData
 */
class Ober<T>(initialValue: T? = null) {
    // 观察者集合，CopyOnWriteArrayList 保证遍历安全
    private val observers = CopyOnWriteArrayList<(T?) -> Unit>()

    @Volatile
    var value: T? = initialValue
        set(newValue) {
            field = newValue
            // 数据变更时通知所有观察者
            dispatchingValue()
        }
    /**
     * 注册观察者，默认粘性：订阅时立刻收到当前最新值
     */
    fun observe(observer: (T?) -> Unit) {
        observers.add(observer)
        // 粘性事件：订阅即回调当前值
        value?.let { observer(it) }
    }
    /**
     * 移除观察者
     */
    fun removeObserver(observer: (T?) -> Unit) {
        observers.remove(observer)
    }

    // 向所有观察者分发数据
    private fun dispatchingValue() {
        val currentValue = value
        observers.forEach { it(currentValue) }
    }
}


class Sol(){
    fun main() {
        // 1. 初始化数据容器，初始值为 0
        val data = Ober(0)

        // 2. 注册第一个观察者
        println("=== 注册观察者A ===")
        data.observe { value ->
            println("观察者A 收到值：$value")
        }

        // 3. 修改一次数据
        println("\n=== 修改数据为 10 ===")
        data.value = 10

        // 4. 注册第二个观察者（此时数据已经是 10 了）
        println("\n=== 注册观察者B ===")
        data.observe { value ->
            println("观察者B 收到值：$value")
        }

        // 5. 再修改一次数据
        println("\n=== 修改数据为 20 ===")
        data.value = 20
    }
}