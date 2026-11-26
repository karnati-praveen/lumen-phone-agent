fn main() {
    uniffi::generate_scaffolding("src/aegis_broker.udl")
        .expect("Failed to generate UniFFI scaffolding for aegis_broker");
}
