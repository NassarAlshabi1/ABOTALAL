package com.example.data.repository

/** Version-aware, read-only diagnostic helpers. Findings are heuristics, never automatic fixes. */
internal object AiDoctorDiagnostics {
    internal data class ReadSpec(
        val group: String,
        val key: String,
        val path: String,
        val properties: String,
        val query: String = "",
        val limit: Int = 100
    )

    internal data class Finding(
        val severity: String,
        val category: String,
        val title: String,
        val evidence: String,
        val recommendation: String
    )

    fun majorVersion(version: String): Int? = RouterOsSupport.parseVersion(version)?.first

    fun additionalReadSpecs(version: String, groups: Set<String>): List<ReadSpec> {
        if (!RouterOsSupport.isSupported(version)) return emptyList()
        val selected = if ("all" in groups) setOf("security", "wireless", "routing", "ipv6", "usermanager") else groups
        val specs = mutableListOf<ReadSpec>()
        if ("usermanager" in selected) {
            addUserManagerSpecs(specs, "/tool/user-manager")
        }
        if ("security" in selected) {
            specs += ReadSpec("security", "management_services", "/ip/service/print", "name,disabled,address,port", limit = 80)
            // Do not request SNMP community names: on RouterOS they are shared secrets.
            specs += ReadSpec("security", "snmp_access", "/snmp/community/print", "addresses,disabled", limit = 80)
        }
        if ("routing" in selected) {
            specs += ReadSpec("routing", "ipv4_routes", "/ip/route/print", ".id,dst-address,gateway,distance,active,disabled,dynamic", limit = 250)
        }
        if ("ipv6" in selected) {
            specs += ReadSpec("ipv6", "ipv6_addresses", "/ipv6/address/print", ".id,address,interface,advertise,disabled,dynamic", limit = 120)
            specs += ReadSpec("ipv6", "ipv6_routes", "/ipv6/route/print", ".id,dst-address,gateway,distance,active,disabled,dynamic", limit = 250)
            specs += ReadSpec("ipv6", "ipv6_firewall_filter", "/ipv6/firewall/filter/print", ".id,chain,action,protocol,src-address,dst-address,in-interface,out-interface,connection-state,disabled,packets,bytes", limit = 200)
        }
        if ("wireless" in selected) {
            addLegacyWirelessSpecs(specs, "/interface/wireless", "wireless")
        }
        return specs
    }

    /** Read-only RouterOS v6 User Manager data. */
    private fun addUserManagerSpecs(target: MutableList<ReadSpec>, prefix: String) {
        target += ReadSpec(
            "usermanager", "um_users", "$prefix/user/print",
            ".id,name,actual-profile,uptime-used,disabled", limit = 500
        )
        target += ReadSpec(
            "usermanager", "um_profiles", "$prefix/profile/print",
            "name,validity,price", limit = 120
        )
        target += ReadSpec(
            "usermanager", "um_user_profiles", "$prefix/user-profile/print",
            "user,profile,state", limit = 500
        )
    }

    private fun addLegacyWirelessSpecs(target: MutableList<ReadSpec>, prefix: String, keyPrefix: String) {
        target += ReadSpec("wireless", "${keyPrefix}_interfaces", "$prefix/print", ".id,name,mode,band,frequency,channel-width,disabled,running,security-profile", limit = 80)
        target += ReadSpec("wireless", "${keyPrefix}_registrations", "$prefix/registration-table/print", "interface,mac-address,signal-strength,tx-rate,rx-rate,uptime", limit = 100)
        target += ReadSpec("wireless", "${keyPrefix}_security_profiles", "$prefix/security-profiles/print", "name,mode,authentication-types,unicast-ciphers,group-ciphers", limit = 80)
    }

    fun securityFindings(
        data: Map<String, List<Map<String, String>>>,
        truncatedKeys: Set<String> = emptySet()
    ): List<Finding> = buildList {
        data["management_services"]?.forEach { row ->
            val name = row["name"].orEmpty().trim().lowercase()
            if (name !in setOf("telnet", "ftp", "www", "api") || isTrue(row["disabled"])) return@forEach
            if (isUnrestricted(row["address"])) {
                add(
                    Finding(
                        severity = if (name == "telnet" || name == "ftp") "high" else "medium",
                        category = "management-service",
                        title = "خدمة إدارة غير مشفّرة متاحة من نطاق واسع",
                        evidence = "الخدمة $name مفعّلة ولا يظهر عليها قيد مصدر محدد في /ip/service.",
                        recommendation = "عطّل الخدمة إن لم تكن لازمة، أو قيّد مصادرها بجانب قواعد الجدار الناري. استخدم SSH أو API-SSL عند الحاجة."
                    )
                )
            }
        }
        data["snmp_access"]?.forEach { row ->
            if (!isTrue(row["disabled"]) && isUnrestricted(row["addresses"])) {
                add(
                    Finding(
                        severity = "medium",
                        category = "snmp",
                        title = "نطاق وصول SNMP غير مقيّد بوضوح",
                        evidence = "يوجد إدخال SNMP فعّال بلا قيد عناوين، أو يسمح بنطاق شامل. لم تُقرأ أسماء المجتمعات أو أسرارها.",
                        recommendation = "قيّد عناوين مصادر SNMP إلى خوادم المراقبة الموثوقة، وراجع إعدادات الجدار الناري."
                    )
                )
            }
        }
        data["dns"]?.firstOrNull()?.let { row ->
            if (isTrue(row["allow-remote-requests"])) {
                add(
                    Finding(
                        severity = "medium",
                        category = "dns",
                        title = "مُحلّل DNS يقبل طلبات بعيدة",
                        evidence = "allow-remote-requests مفعّل؛ لا يثبت هذا وحده أن الخدمة مكشوفة للإنترنت.",
                        recommendation = "إن كان الراوتر لا يخدم عملاء موثوقين، عطّل الطلبات البعيدة أو قيّد DNS بالجدار الناري إلى الشبكات الموثوقة."
                    )
                )
            }
        }
        addFirewallInputFinding(this, data["filter"], "ipv4", "filter" in truncatedKeys)
        addFirewallInputFinding(this, data["ipv6_firewall_filter"], "ipv6", "ipv6_firewall_filter" in truncatedKeys)
        listOf("wireless_security_profiles", "wireless_legacy_security_profiles").forEach { key ->
            data[key]?.forEach { row ->
                if (row["mode"].orEmpty().equals("none", ignoreCase = true)) {
                    add(
                        Finding(
                            severity = "high",
                            category = "wireless",
                            title = "ملف أمان Wireless التقليدي مضبوط على mode=none",
                            evidence = "يذكر توثيق MikroTik أن هذا الوضع لا يستخدم التشفير. قد يكون الملف غير مستخدم حاليًا.",
                            recommendation = "تحقق من الواجهات التي تشير إلى الملف، واستخدم WPA2/WPA3 مناسبًا أو أزل الملف إن لم يكن مستخدمًا."
                        )
                    )
                }
            }
        }
        listOf("wifi_security_profiles").forEach { key ->
            data[key]?.forEach { row ->
                if (row["authentication-types"].isNullOrBlank()) {
                    add(
                        Finding(
                            severity = "info",
                            category = "wireless",
                            title = "تعذّر الحسم من إعدادات مصادقة Wi-Fi دون مراجعة",
                            evidence = "لم تُعرض authentication-types لهذا الملف؛ لم تُقرأ passphrase لأنها قيمة حساسة، لذلك لا يمكن الجزم بأن الشبكة مفتوحة.",
                            recommendation = "تحقق يدويًا من إعدادات الواجهة والملف الفعّال قبل أي تغيير؛ قد تكون هناك مصادقة أو إعدادات موروثة غير ممثلة هنا."
                        )
                    )
                }
            }
        }
        data["um_users"]?.let { rows ->
            val expired = rows.count { row ->
                val actual = row["actual-profile"].orEmpty().trim()
                val uptime = row["uptime-used"].orEmpty().trim()
                (actual.isEmpty() || actual.equals("none", ignoreCase = true)) &&
                    uptime.isNotEmpty() && !uptime.equals("0s", ignoreCase = true)
            }
            if (expired > 0) {
                add(
                    Finding(
                        severity = "info",
                        category = "user-manager",
                        title = "كروت منتهية في User Manager",
                        evidence = "ظهر $expired مستخدمًا بلا actual-profile مع uptime-used غير صفري. هذا مؤشر انتهاء الكرت في v6.",
                        recommendation = "راجع الكروت المنتهية وأرشفها قبل الحذف؛ لا تحذف مستخدمًا لمجرد غياب actual-profile إذا كان uptime-used صفريًا أو غير معروف."
                    )
                )
            }
        }
        data["um_user_profiles"]?.let { rows ->
            val used = rows.count { it["state"].orEmpty().equals("used", ignoreCase = true) }
            if (used > 0) {
                add(
                    Finding(
                        severity = "info",
                        category = "user-manager",
                        title = "ملفات باقة منتهية بحالة used",
                        evidence = "ظهر $used سجل User Profile بحالة used؛ هذه باقات منتهية ولا يمكن تفعيلها مرة أخرى.",
                        recommendation = "طابقها مع المستخدمين قبل أي تنظيف، وتأكد من عدم وجود باقة أحدث فعّالة لنفس المستخدم."
                    )
                )
            }
        }
    }

    private fun addFirewallInputFinding(target: MutableList<Finding>, rows: List<Map<String, String>>?, family: String, truncated: Boolean) {
        if (rows == null) return
        if (truncated) {
            target += Finding(
                severity = "info",
                category = "firewall",
                title = "تعذر تقييم نهاية سلسلة input لـ$family لأن قائمة القواعد اقتُطعت",
                evidence = "عدد القواعد تجاوز حد القراءة؛ لا يمكن استنتاج ترتيب آخر قاعدة من العينة وحدها.",
                recommendation = "راجع القائمة كاملة يدويًا قبل اعتبار الجدار الناري مكتملًا أو ناقصًا."
            )
            return
        }
        val inputRules = rows.filter {
            it["chain"].orEmpty().equals("input", ignoreCase = true) && !isTrue(it["disabled"])
        }
        val lastAction = inputRules.lastOrNull()?.get("action").orEmpty().lowercase()
        if (lastAction !in setOf("drop", "reject")) {
            target += Finding(
                severity = "medium",
                category = "firewall",
                title = "نهاية سلسلة input لـ$family غير مؤكدة الحجب",
                evidence = if (inputRules.isEmpty()) "لم تظهر قواعد input فعّالة في جدول $family." else "آخر قاعدة input فعّالة ظاهرة هي $lastAction، وليست drop/reject.",
                recommendation = "راجع ترتيب القواعد والسلاسل المحوّلة (jump) وسياسة السلسلة يدويًا. هذا فحص تقريبي لا يحلل كل دلالات RouterOS."
            )
        }
    }

    private fun isTrue(value: String?): Boolean = value?.trim()?.lowercase() in setOf("yes", "true", "1", "on")

    private fun isUnrestricted(value: String?): Boolean {
        val addresses = value?.trim().orEmpty()
        if (addresses.isEmpty()) return true
        return addresses.split(',', ';', '\n').any { it.trim() in setOf("0.0.0.0/0", "::/0") }
    }
}
