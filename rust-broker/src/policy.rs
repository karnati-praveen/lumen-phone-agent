use std::collections::{HashMap, HashSet};

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum RiskLevel {
    Level0HardBlocked = 0,
    Level1AutoAllowed = 1,
    Level2ExplicitConfirm = 2,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum AppPolicyTier {
    HardBlocked,
    AskBeforeSend,
    ReadOnly,
    AutoAllowed,
}

#[derive(Debug, Clone)]
pub struct PolicyRuleEntry {
    pub package_name: String,
    pub tier: AppPolicyTier,
    pub is_builtin_hard_block: bool,
}

pub struct PolicyEngine {
    builtin_hard_blocked: HashSet<String>,
    hard_blocked_packages: HashSet<String>,
    hard_blocked_prefixes: Vec<String>,
    app_tiers: HashMap<String, AppPolicyTier>,
    pub strict_confirm_before_send: bool,
    pub auto_wipe_ram_screenshots: bool,
    pub volume_down_kill_switch: bool,
}

impl Default for PolicyEngine {
    fn default() -> Self {
        let mut builtin = HashSet::new();
        for pkg in [
            // UPI & Indian Payment Apps
            "com.google.android.apps.nbu.paisa.user", // Google Pay
            "com.phonepe.app",                        // PhonePe
            "net.one97.paytm",                        // Paytm
            "in.org.npci.upiapp",                     // BHIM UPI
            "com.dreamplug.androidapp",               // CRED
            "in.amazon.mshop.android.shopping",       // Amazon Pay flow guard
            // Banking Apps
            "com.sbi.lotusintouch",                   // YONO SBI
            "com.snapwork.hdfc",                      // HDFC Bank
            "com.csam.icici.bank.imobile",            // iMobile Pay
            "com.axis.mobile",                        // Axis Mobile
            "com.msf.kbank.mobile",                   // Kotak Mobile Banking
            // Password Managers & 2FA
            "com.x8bit.bitwarden",                    // Bitwarden
            "com.onepassword.android",                // 1Password
            "keepass2android.keepass2android",        // Keepass2Android
            "com.google.android.apps.authenticator2", // Google Authenticator
            "com.authy.authy",                        // Authy
            // Android System Security, Installer, Telecom & Wallet
            "com.android.settings",
            "com.google.android.gms.auth",
            "com.android.packageinstaller",
            "com.google.android.packageinstaller",
            "com.android.vending",                    // Play Store purchases/installs
            "com.google.android.apps.walletnfcrel",   // Google Wallet
            "com.samsung.android.spay",               // Samsung Pay / Wallet
            "com.samsung.android.authfw",             // Samsung Pass
        ] {
            builtin.insert(pkg.to_string());
        }

        let mut app_tiers = HashMap::new();
        app_tiers.insert("com.whatsapp".to_string(), AppPolicyTier::AskBeforeSend);
        app_tiers.insert("com.google.android.gm".to_string(), AppPolicyTier::AskBeforeSend);
        app_tiers.insert("com.android.chrome".to_string(), AppPolicyTier::AskBeforeSend);
        app_tiers.insert("com.android.gallery3d".to_string(), AppPolicyTier::ReadOnly);
        app_tiers.insert("com.google.android.apps.photos".to_string(), AppPolicyTier::ReadOnly);
        app_tiers.insert("com.sec.android.gallery3d".to_string(), AppPolicyTier::ReadOnly);
        app_tiers.insert("com.google.android.keep".to_string(), AppPolicyTier::AutoAllowed);
        app_tiers.insert("com.samsung.android.app.notes".to_string(), AppPolicyTier::AutoAllowed);

        Self {
            hard_blocked_packages: builtin.clone(),
            builtin_hard_blocked: builtin,
            hard_blocked_prefixes: vec![
                "com.bank.".to_string(),
                "in.co.bank".to_string(),
                "com.upi.".to_string(),
                "com.wallet.".to_string(),
                "com.pay.".to_string(),
            ],
            app_tiers,
            strict_confirm_before_send: true,
            auto_wipe_ram_screenshots: true,
            volume_down_kill_switch: true,
        }
    }
}

impl PolicyEngine {
    pub fn is_immutable_hard_block(&self, package: &str) -> bool {
        let lower = package.trim().to_ascii_lowercase();
        if self.builtin_hard_blocked.contains(&lower) {
            return true;
        }
        self.hard_blocked_prefixes
            .iter()
            .any(|prefix| lower.starts_with(prefix))
    }

    pub fn is_hard_blocked(&self, package: &str, is_flag_secure: bool) -> bool {
        if is_flag_secure {
            return true;
        }
        let lower = package.trim().to_ascii_lowercase();
        if self.hard_blocked_packages.contains(&lower) || self.builtin_hard_blocked.contains(&lower) {
            return true;
        }
        self.hard_blocked_prefixes
            .iter()
            .any(|prefix| lower.starts_with(prefix))
    }

    pub fn classify_action(
        &self,
        target_package: &str,
        action_type: &str,
        is_flag_secure: bool,
    ) -> RiskLevel {
        if self.is_hard_blocked(target_package, is_flag_secure) {
            return RiskLevel::Level0HardBlocked;
        }

        let lower = target_package.trim().to_ascii_lowercase();
        let action = action_type.trim().to_ascii_uppercase();

        // Hard Rule 1: Destructive, financial, APK-install, and permission-mutating actions are permanently Hard-Blocked
        if matches!(
            action.as_str(),
            "INITIATE_PAYMENT"
                | "OPEN_UPI_URI"
                | "DELETE_FILE"
                | "INSTALL_APK"
                | "UNINSTALL_APP"
                | "GRANT_PERMISSION"
                | "CHANGE_SETTING"
                | "EXPORT_CREDENTIALS"
        ) {
            return RiskLevel::Level0HardBlocked;
        }

        // Hard Rule 2: ReadOnly apps strictly forbid any mutating/clicking/typing action
        if let Some(AppPolicyTier::ReadOnly) = self.app_tiers.get(&lower) {
            if !matches!(action.as_str(), "READ_DOM" | "VISION_SCAN" | "SUMMARIZE_LOCAL") {
                return RiskLevel::Level0HardBlocked;
            }
            return RiskLevel::Level1AutoAllowed;
        }

        // Hard Rule 3: Outbound communications & form submissions always require explicit confirm
        if matches!(
            action.as_str(),
            "SEND_MESSAGE" | "SEND_EMAIL" | "POST_PUBLIC" | "SUBMIT_FORM" | "CLICK_SEND"
        ) {
            return RiskLevel::Level2ExplicitConfirm;
        }

        // Hard Rule 4: Mutating local drafts, calendar events, toggles, clicks
        if matches!(
            action.as_str(),
            "TYPE_DRAFT"
                | "CREATE_CALENDAR_EVENT"
                | "EDIT_LOCAL_FILE"
                | "TOGGLE_SETTING"
                | "CLICK_NODE"
        ) {
            if let Some(AppPolicyTier::AutoAllowed) = self.app_tiers.get(&lower) {
                if action == "CLICK_NODE" {
                    return RiskLevel::Level1AutoAllowed;
                }
            }
            return RiskLevel::Level2ExplicitConfirm;
        }

        // Level 1: Read-only / benign local operations
        RiskLevel::Level1AutoAllowed
    }

    /// Built-in financial, banking, auth, and system security packages are immutable Hard Blocks
    /// and can never be downgraded at runtime. User-added Hard Blocks can be adjusted safely.
    pub fn update_app_tier(
        &mut self,
        package: &str,
        new_tier: AppPolicyTier,
    ) -> Result<(), &'static str> {
        let lower = package.trim().to_ascii_lowercase();
        if lower.is_empty() {
            return Err("Package name cannot be empty");
        }
        if self.is_immutable_hard_block(&lower) && new_tier != AppPolicyTier::HardBlocked {
            return Err("Immutable Hard Rule: built-in financial, auth, and system apps cannot be unlocked");
        }
        if new_tier == AppPolicyTier::HardBlocked {
            self.hard_blocked_packages.insert(lower.clone());
        } else {
            self.hard_blocked_packages.remove(&lower);
        }
        self.app_tiers.insert(lower, new_tier);
        Ok(())
    }

    pub fn list_rules(&self) -> Vec<PolicyRuleEntry> {
        let mut entries: Vec<PolicyRuleEntry> = Vec::new();
        for pkg in &self.hard_blocked_packages {
            entries.push(PolicyRuleEntry {
                package_name: pkg.clone(),
                tier: AppPolicyTier::HardBlocked,
                is_builtin_hard_block: self.builtin_hard_blocked.contains(pkg),
            });
        }
        for (pkg, tier) in &self.app_tiers {
            if !self.hard_blocked_packages.contains(pkg) {
                entries.push(PolicyRuleEntry {
                    package_name: pkg.clone(),
                    tier: *tier,
                    is_builtin_hard_block: false,
                });
            }
        }
        entries.sort_by(|a, b| a.package_name.cmp(&b.package_name));
        entries
    }
}
