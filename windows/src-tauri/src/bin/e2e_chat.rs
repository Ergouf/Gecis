use std::time::Duration;

fn main() {
    let prompt = std::env::args()
        .nth(1)
        .unwrap_or_else(|| "只回复两个字：收到".into());
    println!("E2E prompt: {prompt}");
    match gecis_windows_lib::runtime_e2e(&prompt, Duration::from_secs(90)) {
        Ok(text) => {
            println!("E2E OK");
            println!("{text}");
            std::process::exit(0);
        }
        Err(err) => {
            eprintln!("E2E FAIL: {err}");
            std::process::exit(1);
        }
    }
}
