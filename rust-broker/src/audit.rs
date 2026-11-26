use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::sync::Mutex;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct AuditReceipt {
    pub seq: u64,
    pub timestamp_ms: u64,
    pub outcome: String,
    pub target_package: String,
    pub action_summary: String,
    pub token_id: String,
    pub prev_hash: String,
    pub entry_hash: String,
    pub screenshot_wiped: bool,
    pub token_json: String,
}

/// Append-only SHA-256 hash-chained local Receipt Book.
/// In Android production, rows are persisted inside SQLCipher (AES-256-CBC + HMAC-SHA512)
/// keyed by Android Hardware Keystore.
pub struct AuditLog {
    entries: Mutex<Vec<AuditReceipt>>,
}

impl AuditLog {
    pub fn new() -> Self {
        Self {
            entries: Mutex::new(Vec::new()),
        }
    }

    pub fn from_entries(entries: Vec<AuditReceipt>) -> Self {
        Self {
            entries: Mutex::new(entries),
        }
    }

    pub fn append(
        &self,
        timestamp_ms: u64,
        outcome: &str,
        target_package: &str,
        action_summary: &str,
        token_id: &str,
        screenshot_wiped: bool,
        token_json: &str,
    ) -> AuditReceipt {
        let mut guard = self.entries.lock().expect("audit mutex poisoned");
        let seq = (guard.len() as u64) + 1;
        let prev_hash = guard
            .last()
            .map(|e| e.entry_hash.clone())
            .unwrap_or_else(|| "0".repeat(64));

        let entry_hash = Self::compute_hash(
            seq,
            timestamp_ms,
            outcome,
            target_package,
            action_summary,
            token_id,
            &prev_hash,
            screenshot_wiped,
            token_json,
        );

        let receipt = AuditReceipt {
            seq,
            timestamp_ms,
            outcome: outcome.to_string(),
            target_package: target_package.to_string(),
            action_summary: action_summary.to_string(),
            token_id: token_id.to_string(),
            prev_hash,
            entry_hash,
            screenshot_wiped,
            token_json: token_json.to_string(),
        };
        guard.push(receipt.clone());
        receipt
    }

    pub fn list(&self, filter_outcome: Option<String>) -> Vec<AuditReceipt> {
        let guard = self.entries.lock().expect("audit mutex poisoned");
        match filter_outcome {
            Some(filter) if !filter.is_empty() && filter != "ALL" => guard
                .iter()
                .filter(|r| r.outcome.eq_ignore_ascii_case(&filter))
                .cloned()
                .collect(),
            _ => guard.clone(),
        }
    }

    pub fn verify_chain(&self) -> bool {
        let guard = self.entries.lock().expect("audit mutex poisoned");
        let mut expected_prev = "0".repeat(64);
        for item in guard.iter() {
            if item.prev_hash != expected_prev {
                return false;
            }
            let recomputed = Self::compute_hash(
                item.seq,
                item.timestamp_ms,
                &item.outcome,
                &item.target_package,
                &item.action_summary,
                &item.token_id,
                &item.prev_hash,
                item.screenshot_wiped,
                &item.token_json,
            );
            if recomputed != item.entry_hash {
                return false;
            }
            expected_prev = item.entry_hash.clone();
        }
        true
    }

    pub fn clear_all(&self) {
        if let Ok(mut guard) = self.entries.lock() {
            guard.clear();
        }
    }

    #[allow(clippy::too_many_arguments)]
    fn compute_hash(
        seq: u64,
        timestamp_ms: u64,
        outcome: &str,
        target_package: &str,
        action_summary: &str,
        token_id: &str,
        prev_hash: &str,
        screenshot_wiped: bool,
        token_json: &str,
    ) -> String {
        let mut hasher = Sha256::new();
        hasher.update(format!(
            "{}|{}|{}|{}|{}|{}|{}|{}|{}",
            seq,
            timestamp_ms,
            outcome,
            target_package,
            action_summary,
            token_id,
            prev_hash,
            screenshot_wiped,
            token_json
        ));
        hex::encode(hasher.finalize())
    }
}
