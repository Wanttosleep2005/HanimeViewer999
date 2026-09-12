package io.github.daisukikaffuchino.han1meviewer.logic.model

/**
 * nJAV 女优索引（`https://njavtv.com/cn/actresses`）里的一位女优。
 *
 * 索引页的卡片结构（2026-09 实测）：
 *
 * ```html
 * <li>
 *   <div class="space-y-4">
 *     <a href="https://njavtv.com/dm288/cn/actresses/%E6%B3%A2%E5%A4%9A%E9%87%8E%E7%B5%90%E8%A1%A3">
 *       <div class="… h-20 w-20 rounded-full …">
 *         <img src="https://fourhoi.com/actress/26225-t.jpg" alt="波多野结衣">
 *       </div>
 *     </a>
 *     <div class="space-y-2">
 *       <a href="…">
 *         <h4 class="… truncate">波多野结衣</h4>
 *         <p class="…">5668 条影片</p>
 *         <p class="…">2008 出道</p>
 *       </a>
 *     </div>
 *   </div>
 * </li>
 * ```
 *
 * ⚠️ [path] 是**从卡片 `href` 上抠下来的尾段**，不是拿 [name] 现编码出来的。
 * 站点链接里用的是繁体写法（「波多野**結**衣」），而界面显示的是简体
 * （「波多野结衣」），两者百分号编码不同（`%E7%B5%90` vs `%E7%BB%93`）。
 * 照抄 `href` 才能和站点完全一致，顺带把会变的 `dm###` 前缀丢掉。
 */
data class NjavActress(
    /** 界面显示名（站点 /cn/ 下给的是简体）。 */
    val name: String,
    /** 头像直链（`fourhoi.com/actress/…-t.jpg`）。 */
    val avatarUrl: String,
    /** 「5668 条影片」里的 5668；解析不出来时为 null。 */
    val videoCount: Int?,
    /** 「2008 出道」里的 2008；解析不出来时为 null。 */
    val debutYear: Int?,
    /** 形如 `actresses/%E6%B3%A2%E5%A4%9A%E9%87%8E%E7%B5%90%E8%A1%A3`。 */
    val path: String,
)
