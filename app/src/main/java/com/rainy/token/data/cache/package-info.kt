/**
 * 余额本地缓存层（非加密）。断网时 UI 仍可展示 stale 数据。
 * 用 DataStore<Preferences> 存最近一次成功获取的余额快照。
 */
package com.rainy.token.data.cache
