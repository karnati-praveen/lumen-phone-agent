use chacha20poly1305::{
    aead::{Aead, KeyInit},
    ChaCha20Poly1305, Nonce,
};
use rand::RngCore;
use std::fs::{self, File, OpenOptions};
use std::io::{BufRead, BufReader, Write};
use std::path::Path;
use zeroize::Zeroize;

/// Encrypted Local Memory Vault using ChaCha20-Poly1305 (256-bit AEAD)
/// with key material zeroized in RAM upon drop or biometric vault shred.
pub struct EncryptedVault {
    db_path: String,
    key_material: [u8; 32],
    in_memory_only: bool,
    is_shredded: bool,
}

impl EncryptedVault {
    pub fn open(db_path: &str, vault_key_hex: &str) -> Result<Self, &'static str> {
        let mut raw = hex::decode(vault_key_hex).map_err(|_| "Invalid hex vault key")?;
        if raw.len() < 32 {
            raw.zeroize();
            return Err("Vault key must be at least 256 bits");
        }
        let mut key_material = [0u8; 32];
        key_material.copy_from_slice(&raw[..32]);
        raw.zeroize();

        let in_memory_only = db_path.trim().is_empty() || db_path == ":memory:";
        if !in_memory_only {
            if let Some(parent) = Path::new(db_path).parent() {
                if !parent.as_os_str().is_empty() {
                    fs::create_dir_all(parent).map_err(|_| "Failed to create vault directory")?;
                }
            }
        }

        Ok(Self {
            db_path: db_path.to_string(),
            key_material,
            in_memory_only,
            is_shredded: false,
        })
    }

    pub fn db_path(&self) -> &str {
        &self.db_path
    }

    pub fn append_encrypted_record(&self, plaintext_json: &str) -> Result<(), &'static str> {
        if self.is_shredded || self.in_memory_only {
            return Ok(());
        }
        let cipher = ChaCha20Poly1305::new((&self.key_material).into());
        let mut nonce_bytes = [0u8; 12];
        rand::thread_rng().fill_bytes(&mut nonce_bytes);
        let nonce = Nonce::from(nonce_bytes);
        let ciphertext = cipher
            .encrypt(&nonce, plaintext_json.as_bytes())
            .map_err(|_| "AEAD encryption failed")?;

        let mut file = OpenOptions::new()
            .create(true)
            .append(true)
            .open(&self.db_path)
            .map_err(|_| "Failed to open vault file")?;
        writeln!(file, "{}:{}", hex::encode(nonce_bytes), hex::encode(ciphertext))
            .map_err(|_| "Failed to write encrypted record")?;
        Ok(())
    }

    pub fn load_records(&self) -> Result<Vec<String>, &'static str> {
        if self.is_shredded || self.in_memory_only || !Path::new(&self.db_path).exists() {
            return Ok(Vec::new());
        }
        let cipher = ChaCha20Poly1305::new((&self.key_material).into());
        let file = File::open(&self.db_path).map_err(|_| "Failed to read vault file")?;
        let reader = BufReader::new(file);
        let mut records = Vec::new();
        for line in reader.lines() {
            let line = line.map_err(|_| "Failed to read vault line")?;
            let trimmed = line.trim();
            if trimmed.is_empty() {
                continue;
            }
            let Some((nonce_hex, ct_hex)) = trimmed.split_once(':') else {
                return Err("Corrupt vault record framing");
            };
            let nonce_bytes = hex::decode(nonce_hex).map_err(|_| "Corrupt nonce hex")?;
            let ct_bytes = hex::decode(ct_hex).map_err(|_| "Corrupt ciphertext hex")?;
            let nonce_arr: [u8; 12] = nonce_bytes
                .try_into()
                .map_err(|_| "Invalid nonce length")?;
            let plaintext = cipher
                .decrypt(&Nonce::from(nonce_arr), ct_bytes.as_ref())
                .map_err(|_| "Vault AEAD authentication failed")?;
            let s = String::from_utf8(plaintext).map_err(|_| "Invalid UTF-8 in vault record")?;
            records.push(s);
        }
        Ok(records)
    }

    pub fn shred(&mut self) {
        if !self.in_memory_only && Path::new(&self.db_path).exists() {
            if let Ok(meta) = fs::metadata(&self.db_path) {
                if let Ok(mut file) = OpenOptions::new().write(true).open(&self.db_path) {
                    let zeros = vec![0u8; meta.len().min(65536) as usize];
                    let mut remaining = meta.len();
                    while remaining > 0 {
                        let chunk = remaining.min(zeros.len() as u64) as usize;
                        if file.write_all(&zeros[..chunk]).is_err() {
                            break;
                        }
                        remaining -= chunk as u64;
                    }
                    let _ = file.sync_all();
                }
            }
            let _ = fs::remove_file(&self.db_path);
        }
        self.key_material.zeroize();
        self.is_shredded = true;
    }
}

impl Drop for EncryptedVault {
    fn drop(&mut self) {
        self.key_material.zeroize();
    }
}
