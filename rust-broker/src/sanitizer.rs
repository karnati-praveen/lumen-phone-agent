use rand::RngCore;
use unicode_normalization::UnicodeNormalization;

#[derive(Debug, Clone)]
pub struct SanitizedContext {
    pub boundary_tag: String,
    pub wrapped_text: String,
    pub threat_detected: bool,
    pub threat_reason: String,
    pub matched_snippet: String,
}

/// Hardened multi-stage prompt-injection sanitizer, outbound payload firewall & intent provenance guard.
/// Closes NFKC homoglyphs, zero-width/bidi spoofing, URI scheme hijacks, credential/OTP exfiltration,
/// and cross-package intent expansion from untrusted screen content.
pub struct PromptSanitizer;

pub const MAX_OUTBOUND_PAYLOAD_CHARS: usize = 1_500;

const INJECTION_PATTERNS: &[(&str, &str)] = &[
    ("ignore previous instructions", "Instruction override attempt detected in untrusted content"),
    ("ignore all prior instructions", "Instruction override attempt detected in untrusted content"),
    ("disregard system prompt", "System prompt override attempt detected"),
    ("override security policy", "Policy bypass directive detected in untrusted content"),
    ("bypass security gate", "Security gate bypass attempt detected"),
    ("bypass hard rule", "Hard-rule bypass attempt detected"),
    ("bypass biometric", "Legacy security bypass attempt detected"),
    ("disable kill switch", "Kill switch sabotage attempt detected"),
    ("send rs.", "Unauthorized financial transfer instruction in untrusted screen text"),
    ("transfer rs.", "Unauthorized financial transfer instruction in untrusted screen text"),
    ("transfer money", "Unauthorized financial transfer instruction in untrusted screen text"),
    ("open google pay", "Unauthorized Level 0 payment app invocation from untrusted content"),
    ("open phonepe", "Unauthorized Level 0 payment app invocation from untrusted content"),
    ("open paytm", "Unauthorized Level 0 payment app invocation from untrusted content"),
    ("open bitwarden", "Unauthorized password manager access from untrusted content"),
    ("export passwords", "Credential exfiltration attempt detected"),
    ("forward otp", "OTP exfiltration attempt detected"),
    ("send otp", "OTP exfiltration attempt detected"),
    ("share verification code", "2FA/OTP exfiltration attempt detected"),
    ("upi://pay", "Blocked UPI payment deep-link scheme in untrusted content"),
    ("tez://upi", "Blocked Google Pay deep-link scheme in untrusted content"),
    ("gpay://", "Blocked Google Pay deep-link scheme in untrusted content"),
    ("phonepe://pay", "Blocked PhonePe deep-link scheme in untrusted content"),
    ("paytmmp://pay", "Blocked Paytm deep-link scheme in untrusted content"),
    ("bhim://", "Blocked BHIM UPI deep-link scheme in untrusted content"),
    ("intent://", "Blocked Android intent URI hijack in untrusted content"),
    ("javascript:", "Blocked script URI injection in untrusted content"),
    ("data:text/html", "Blocked HTML data URI injection in untrusted content"),
    ("curl http", "Blocked network exfiltration command in untrusted content"),
    ("exfiltrate", "Data exfiltration directive detected"),
    ("new system directive", "Role-injection boundary breakout attempt detected"),
    ("assistant:", "Role-spoofing delimiter injection detected"),
    ("<|im_start|>", "ChatML role-delimiter breakout attempt detected"),
    ("<|system|>", "System-tag role breakout attempt detected"),
    ("[system]", "Bracketed system-role injection detected"),
];

const ALWAYS_BLOCKED_IN_UNTRUSTED: &[&str] = &[
    "ignore previous instructions",
    "ignore all prior instructions",
    "disregard system prompt",
    "override security policy",
    "bypass security gate",
    "bypass hard rule",
    "bypass biometric",
    "disable kill switch",
    "new system directive",
    "<|im_start|>",
    "<|system|>",
    "[system]",
];

const FORBIDDEN_OUTBOUND_PATTERNS: &[(&str, &str)] = &[
    ("upi://", "Outbound payment URI scheme blocked by hard rule"),
    ("tez://", "Outbound Google Pay URI scheme blocked by hard rule"),
    ("gpay://", "Outbound payment URI scheme blocked by hard rule"),
    ("phonepe://", "Outbound PhonePe URI scheme blocked by hard rule"),
    ("paytmmp://", "Outbound Paytm URI scheme blocked by hard rule"),
    ("bhim://", "Outbound BHIM URI scheme blocked by hard rule"),
    ("intent://", "Outbound Android intent URI blocked by hard rule"),
    ("javascript:", "Outbound script URI blocked by hard rule"),
    ("data:text/html", "Outbound HTML payload blocked by hard rule"),
    ("otp is", "Outbound OTP sharing blocked by hard rule"),
    ("my otp", "Outbound OTP sharing blocked by hard rule"),
    ("verification code is", "Outbound 2FA code sharing blocked by hard rule"),
    ("upi pin", "Outbound UPI PIN sharing blocked by hard rule"),
    ("mpin", "Outbound banking MPIN sharing blocked by hard rule"),
    ("cvv", "Outbound card CVV sharing blocked by hard rule"),
    ("seed phrase", "Outbound wallet seed phrase blocked by hard rule"),
    ("recovery phrase", "Outbound wallet recovery phrase blocked by hard rule"),
    ("private key", "Outbound private key sharing blocked by hard rule"),
    ("sk-live-", "Outbound secret API token blocked by hard rule"),
];

impl PromptSanitizer {
    /// Strips zero-width, soft-hyphen, bidi override, and combining diacritic marks,
    /// then applies NFKC normalization and Cyrillic/Greek confusable folding so homoglyph
    /// attacks cannot evade detection.
    pub fn normalize_for_inspection(input: &str) -> String {
        let stripped: String = input
            .chars()
            .filter(|&c| {
                !matches!(
                    c,
                    '\u{00AD}'
                        | '\u{0300}'..='\u{036F}'
                        | '\u{200B}'..='\u{200F}'
                        | '\u{202A}'..='\u{202E}'
                        | '\u{2060}'..='\u{206F}'
                        | '\u{FEFF}'
                )
            })
            .map(fold_confusable)
            .collect();

        stripped
            .nfkc()
            .collect::<String>()
            .to_ascii_lowercase()
            .split_whitespace()
            .collect::<Vec<_>>()
            .join(" ")
    }

    /// Generates a cryptographically random per-session boundary delimiter so untrusted
    /// DOM/OCR/PDF text cannot close the delimiter and inject system instructions.
    pub fn random_boundary_tag() -> String {
        let mut bytes = [0u8; 8];
        rand::thread_rng().fill_bytes(&mut bytes);
        format!("AEGIS_UNTRUSTED_{}", hex::encode(bytes))
    }

    pub fn sanitize_and_inspect(user_command: &str, raw_screen_text: &str) -> SanitizedContext {
        let normalized_screen = Self::normalize_for_inspection(raw_screen_text);
        let normalized_cmd = Self::normalize_for_inspection(user_command);
        let tag = Self::random_boundary_tag();

        for (pattern, reason) in INJECTION_PATTERNS {
            let always_block = ALWAYS_BLOCKED_IN_UNTRUSTED.contains(pattern);
            if normalized_screen.contains(pattern) && (always_block || !normalized_cmd.contains(pattern)) {
                return SanitizedContext {
                    boundary_tag: tag.clone(),
                    wrapped_text: format!("<{0}>\n[REDACTED_BY_AEGIS_BROKER]\n</{0}>", tag),
                    threat_detected: true,
                    threat_reason: (*reason).to_string(),
                    matched_snippet: (*pattern).to_string(),
                };
            }
        }

        let safe_body = raw_screen_text.replace(&tag, "[FILTERED_TAG]");
        SanitizedContext {
            boundary_tag: tag.clone(),
            wrapped_text: format!("<{0}>\n{1}\n</{0}>", tag, safe_body),
            threat_detected: false,
            threat_reason: String::new(),
            matched_snippet: String::new(),
        }
    }

    /// Hard Outbound Payload Firewall:
    /// Blocks secret/OTP/PIN/seed/CVV leakage, dangerous URI schemes, and oversized payloads.
    pub fn validate_outbound_payload(action_type: &str, payload_text: &str) -> Result<(), String> {
        let act = action_type.trim().to_ascii_uppercase();
        let is_mutating = matches!(
            act.as_str(),
            "SEND_MESSAGE"
                | "SEND_EMAIL"
                | "POST_PUBLIC"
                | "SUBMIT_FORM"
                | "CLICK_SEND"
                | "TYPE_DRAFT"
                | "EDIT_LOCAL_FILE"
        );
        if !is_mutating {
            return Ok(());
        }

        if payload_text.chars().count() > MAX_OUTBOUND_PAYLOAD_CHARS {
            return Err(format!(
                "Payload length exceeds hard limit of {} characters",
                MAX_OUTBOUND_PAYLOAD_CHARS
            ));
        }

        let norm = Self::normalize_for_inspection(payload_text);
        for (pat, reason) in FORBIDDEN_OUTBOUND_PATTERNS {
            if norm.contains(pat) {
                return Err((*reason).to_string());
            }
        }

        // Block numeric 4-8 digit standalone codes when accompanied by sensitive keywords in the same payload
        let has_sensitive_kw = norm.contains("otp")
            || norm.contains("pin")
            || norm.contains("cvv")
            || norm.contains("passcode")
            || norm.contains("password");
        if has_sensitive_kw && contains_4_to_8_digit_code(&norm) {
            return Err("Numeric verification/PIN code blocked from outbound payload by hard rule".into());
        }

        Ok(())
    }

    /// Deterministic Intent Provenance check:
    /// Untrusted screen content cannot escalate target packages to sensitive/payment apps
    /// or trigger outbound actions absent from the user's original command.
    pub fn check_intent_provenance(
        user_command: &str,
        target_package: &str,
        action_type: &str,
    ) -> Result<(), String> {
        let cmd = Self::normalize_for_inspection(user_command);
        let pkg = target_package.to_ascii_lowercase();
        let act = action_type.to_ascii_uppercase();

        if pkg.contains("paisa")
            || pkg.contains("phonepe")
            || pkg.contains("paytm")
            || pkg.contains("bank")
            || pkg.contains("bitwarden")
            || pkg.contains("authenticator")
            || pkg.contains("wallet")
        {
            return Err("Intent provenance violation: financial/credential package cannot be targeted".into());
        }

        if matches!(
            act.as_str(),
            "SEND_MESSAGE" | "SEND_EMAIL" | "POST_PUBLIC" | "CLICK_SEND" | "SUBMIT_FORM"
        ) {
            let has_send_intent = cmd.contains("send")
                || cmd.contains("ask")
                || cmd.contains("message")
                || cmd.contains("reply")
                || cmd.contains("whatsapp")
                || cmd.contains("email")
                || cmd.contains("mail")
                || cmd.contains("post")
                || cmd.contains("submit");
            if !has_send_intent {
                return Err(format!(
                    "Intent provenance violation: action '{}' not authorized by user command",
                    act
                ));
            }
        }

        if matches!(act.as_str(), "INITIATE_PAYMENT" | "OPEN_UPI_URI" | "DELETE_FILE") {
            return Err(format!("Action '{}' is forbidden by hard safety rules", act));
        }

        Ok(())
    }
}

fn contains_4_to_8_digit_code(norm: &str) -> bool {
    norm.split(|c: char| !c.is_ascii_alphanumeric())
        .any(|tok| (4..=8).contains(&tok.len()) && tok.chars().all(|c| c.is_ascii_digit()))
}

fn fold_confusable(c: char) -> char {
    match c {
        'а' | 'А' | 'α' | 'Α' => 'a',
        'в' | 'В' | 'β' | 'Β' => 'b',
        'е' | 'Е' | 'ε' | 'Ε' => 'e',
        'і' | 'І' | 'ι' | 'Ι' | 'ӏ' => 'i',
        'к' | 'К' | 'κ' | 'Κ' => 'k',
        'м' | 'М' | 'μ' | 'Μ' => 'm',
        'н' | 'Н' | 'η' | 'Η' => 'h',
        'о' | 'О' | 'ο' | 'Ο' | '０' => 'o',
        'р' | 'Р' | 'ρ' | 'Ρ' => 'p',
        'с' | 'С' | 'ϲ' | 'Ϲ' => 'c',
        'т' | 'Т' | 'τ' | 'Τ' => 't',
        'х' | 'Х' | 'χ' | 'Χ' => 'x',
        'у' | 'У' | 'γ' | 'υ' | 'Υ' => 'y',
        'ѕ' | 'Ѕ' => 's',
        'ν' | 'Ν' => 'v',
        'п' => 'n',
        _ => c,
    }
}
