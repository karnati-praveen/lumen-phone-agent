use ed25519_dalek::{Signature, Signer, SigningKey, Verifier, VerifyingKey};
use rand::RngCore;
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::collections::HashSet;
use std::sync::Mutex;
use uuid::Uuid;

pub const TOKEN_TTL_MS: u64 = 5_000;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct CapabilityToken {
    pub token_id: String,
    pub issued_at_ms: u64,
    pub expires_at_ms: u64,
    pub monotonic_expires_ms: u64,
    pub nonce: String,
    pub target_package: String,
    pub action_type: String,
    pub target_node_hash: String,
    pub payload_hash: String,
    pub risk_level: u8,
    pub user_approval_proof: String,
    pub broker_ed25519_sig: String,
}

pub struct TokenMinter {
    signing_key: SigningKey,
    spent_nonces: Mutex<HashSet<String>>,
    active_nonces: Mutex<HashSet<String>>,
}

impl TokenMinter {
    pub fn new() -> Self {
        let mut seed = [0u8; 32];
        rand::thread_rng().fill_bytes(&mut seed);
        let signing_key = SigningKey::from_bytes(&seed);
        Self {
            signing_key,
            spent_nonces: Mutex::new(HashSet::new()),
            active_nonces: Mutex::new(HashSet::new()),
        }
    }

    pub fn verifying_key(&self) -> VerifyingKey {
        self.signing_key.verifying_key()
    }

    pub fn public_key_hex(&self) -> String {
        hex::encode(self.signing_key.verifying_key().to_bytes())
    }

    pub fn sha256_prefixed(input: &str) -> String {
        let mut hasher = Sha256::new();
        hasher.update(input.as_bytes());
        format!("sha256:{}", hex::encode(hasher.finalize()))
    }

    pub fn mint(
        &self,
        target_package: &str,
        action_type: &str,
        target_node_hash: &str,
        payload_hash: &str,
        risk_level: u8,
        now_wall_ms: u64,
        now_monotonic_ms: u64,
        user_approval_proof: String,
    ) -> CapabilityToken {
        let mut nonce_bytes = [0u8; 8];
        rand::thread_rng().fill_bytes(&mut nonce_bytes);
        let nonce = hex::encode(nonce_bytes);
        let token_id = format!("tok_{}", Uuid::new_v4());
        let expires_at_ms = now_wall_ms.saturating_add(TOKEN_TTL_MS);
        let monotonic_expires_ms = now_monotonic_ms.saturating_add(TOKEN_TTL_MS);

        let signing_msg = Self::canonical_token_bytes(
            &token_id,
            now_wall_ms,
            expires_at_ms,
            monotonic_expires_ms,
            &nonce,
            target_package,
            action_type,
            target_node_hash,
            payload_hash,
            risk_level,
            &user_approval_proof,
        );
        let sig: Signature = self.signing_key.sign(&signing_msg);

        if let Ok(mut active) = self.active_nonces.lock() {
            active.insert(nonce.clone());
        }

        CapabilityToken {
            token_id,
            issued_at_ms: now_wall_ms,
            expires_at_ms,
            monotonic_expires_ms,
            nonce,
            target_package: target_package.to_string(),
            action_type: action_type.to_string(),
            target_node_hash: target_node_hash.to_string(),
            payload_hash: payload_hash.to_string(),
            risk_level,
            user_approval_proof,
            broker_ed25519_sig: hex::encode(sig.to_bytes()),
        }
    }

    /// Verifies Ed25519 signature, monotonic TTL expiry, anti-clickjacking node hash,
    /// and atomically burns the single-use nonce so replay attempts always fail.
    pub fn verify_and_burn(
        &self,
        token: &CapabilityToken,
        live_package: &str,
        live_node_hash: &str,
        now_monotonic_ms: u64,
    ) -> Result<(), &'static str> {
        if now_monotonic_ms > token.monotonic_expires_ms {
            return Err("TokenExpired");
        }
        if token.target_package != live_package || token.target_node_hash != live_node_hash {
            return Err("NodeHashMismatch");
        }

        let sig_bytes = hex::decode(&token.broker_ed25519_sig).map_err(|_| "CryptoError")?;
        let sig = Signature::from_slice(&sig_bytes).map_err(|_| "CryptoError")?;
        let msg = Self::canonical_token_bytes(
            &token.token_id,
            token.issued_at_ms,
            token.expires_at_ms,
            token.monotonic_expires_ms,
            &token.nonce,
            &token.target_package,
            &token.action_type,
            &token.target_node_hash,
            &token.payload_hash,
            token.risk_level,
            &token.user_approval_proof,
        );
        self.signing_key
            .verifying_key()
            .verify(&msg, &sig)
            .map_err(|_| "CryptoError")?;

        let mut active = self.active_nonces.lock().map_err(|_| "CryptoError")?;
        let mut spent = self.spent_nonces.lock().map_err(|_| "CryptoError")?;
        if spent.contains(&token.nonce) || !active.remove(&token.nonce) {
            return Err("TokenReplayDetected");
        }
        spent.insert(token.nonce.clone());
        Ok(())
    }

    /// Emergency Kill Switch: immediately revokes every pending active token.
    pub fn revoke_all_active(&self) {
        if let (Ok(mut active), Ok(mut spent)) = (self.active_nonces.lock(), self.spent_nonces.lock()) {
            for nonce in active.drain() {
                spent.insert(nonce);
            }
        }
    }

    #[allow(clippy::too_many_arguments)]
    fn canonical_token_bytes(
        token_id: &str,
        issued_at_ms: u64,
        expires_at_ms: u64,
        monotonic_expires_ms: u64,
        nonce: &str,
        target_package: &str,
        action_type: &str,
        target_node_hash: &str,
        payload_hash: &str,
        risk_level: u8,
        user_approval_proof: &str,
    ) -> Vec<u8> {
        format!(
            "{}|{}|{}|{}|{}|{}|{}|{}|{}|{}|{}",
            token_id,
            issued_at_ms,
            expires_at_ms,
            monotonic_expires_ms,
            nonce,
            target_package,
            action_type,
            target_node_hash,
            payload_hash,
            risk_level,
            user_approval_proof
        )
        .into_bytes()
    }
}
