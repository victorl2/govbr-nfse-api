//! Testes da CLI.
//!
//! O cliente é exercitado contra um servidor HTTP de mentira montado aqui mesmo,
//! com as respostas que o serviço realmente devolve — inclusive as de erro, que
//! são as que decidem o código de saída.

use super::*;
use std::io::{BufRead, BufReader, Read, Write};
use std::net::{TcpListener, TcpStream};
use std::thread;

/// Sobe um servidor que responde uma única requisição com `resposta` e encerra.
/// Devolve a URL base.
fn servidor(status: u16, motivo: &str, tipo: &str, corpo: &str) -> String {
    let listener = TcpListener::bind("127.0.0.1:0").expect("bind");
    let addr = listener.local_addr().expect("addr");
    let resposta = format!(
        "HTTP/1.1 {status} {motivo}\r\nContent-Type: {tipo}\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{corpo}",
        corpo.len()
    );
    thread::spawn(move || {
        if let Ok((stream, _)) = listener.accept() {
            atender(stream, &resposta);
        }
    });
    format!("http://{addr}")
}

/// Lê a requisição inteira (cabeçalhos e corpo) antes de responder: se o corpo
/// ficar sem ser lido, o cliente vê a conexão cair em vez da resposta.
fn atender(mut stream: TcpStream, resposta: &str) {
    let mut reader = BufReader::new(stream.try_clone().expect("clone"));
    let mut tamanho = 0usize;
    loop {
        let mut linha = String::new();
        if reader.read_line(&mut linha).unwrap_or(0) == 0 || linha == "\r\n" {
            break;
        }
        let baixa = linha.to_ascii_lowercase();
        if let Some(valor) = baixa.strip_prefix("content-length:") {
            tamanho = valor.trim().parse().unwrap_or(0);
        }
    }
    if tamanho > 0 {
        let mut corpo = vec![0u8; tamanho];
        let _ = reader.read_exact(&mut corpo);
    }
    let _ = stream.write_all(resposta.as_bytes());
    let _ = stream.flush();
}

fn cliente(url: &str) -> Client {
    Client::new(url, Duration::from_secs(10))
}

// ------------------------------------------------------------ códigos de saída

#[test]
fn autorizada_sai_zero() {
    let v = serde_json::json!({"status": "AUTHORIZED"});
    assert_eq!(codigo_do_status(&v), EXIT_OK);
}

#[test]
fn evento_registrado_sai_zero() {
    let v = serde_json::json!({"status": "REGISTERED"});
    assert_eq!(codigo_do_status(&v), EXIT_OK);
}

#[test]
fn recusas_saem_dois() {
    for status in ["REJECTED_BY_SEFIN", "REJECTED_LOCALLY", "SUBMIT_FAILED"] {
        let v = serde_json::json!({ "status": status });
        assert_eq!(codigo_do_status(&v), EXIT_RECUSADO, "status {status}");
    }
}

#[test]
fn servico_fora_e_sobrecarga_saem_tres() {
    for status in [503u16, 529] {
        let e = Error::Api {
            status,
            message: "x".into(),
            url: "u".into(),
        };
        assert_eq!(codigo_do_erro(&e), EXIT_INDISPONIVEL, "http {status}");
    }
    let inalcancavel = Error::Unreachable {
        url: "u".into(),
        cause: "recusada".into(),
    };
    assert_eq!(codigo_do_erro(&inalcancavel), EXIT_INDISPONIVEL);
}

#[test]
fn nota_inexistente_sai_quatro() {
    let e = Error::Api {
        status: 404,
        message: "no emission recorded".into(),
        url: "u".into(),
    };
    assert_eq!(codigo_do_erro(&e), EXIT_NAO_ENCONTRADO);
}

// ------------------------------------------------------------------- cliente

#[test]
fn saude_devolve_status_e_corpo() {
    let url = servidor(
        200,
        "OK",
        "application/json",
        r#"{"status":"UP","certificate":{"daysToExpiry":144},"warnings":[]}"#,
    );
    let (status, corpo) = cliente(&url).health().expect("health");
    assert_eq!(status, 200);
    assert_eq!(corpo["status"], "UP");
}

#[test]
fn saude_com_certificado_vencido_ainda_traz_o_corpo() {
    // 503 aqui não é falha de transporte: é a resposta que explica o problema,
    // e a CLI precisa conseguir mostrá-la.
    let url = servidor(
        503,
        "Service Unavailable",
        "application/json",
        r#"{"status":"DOWN","warnings":["o certificado expirou"]}"#,
    );
    let (status, corpo) = cliente(&url).health().expect("health");
    assert_eq!(status, 503);
    assert_eq!(corpo["status"], "DOWN");
    assert_eq!(corpo["warnings"][0], "o certificado expirou");
}

#[test]
fn emissao_autorizada_e_lida() {
    let url = servidor(
        200,
        "OK",
        "application/json",
        r#"{"status":"AUTHORIZED","chaveAcesso":"3304557221234567800019500000000000012608127063","findings":[]}"#,
    );
    let resposta = cliente(&url)
        .send(serde_json::json!({"emitter": {}}))
        .expect("send");
    assert_eq!(resposta["status"], "AUTHORIZED");
    assert_eq!(codigo_do_status(&resposta), EXIT_OK);
}

#[test]
fn erro_do_servico_preserva_a_mensagem() {
    // O serviço se explica bem; repetir a redação dele é melhor do que inventar.
    let url = servidor(
        400,
        "Bad Request",
        "application/json",
        r#"{"message":"lastConsumed is required"}"#,
    );
    let erro = cliente(&url).numbering().expect_err("deveria falhar");
    match erro {
        Error::Api {
            status, message, ..
        } => {
            assert_eq!(status, 400);
            assert_eq!(message, "lastConsumed is required");
        }
        outro => panic!("esperava Error::Api, veio {outro:?}"),
    }
}

#[test]
fn corpo_nao_json_vira_erro_legivel() {
    let url = servidor(500, "Internal Server Error", "text/plain", "boom");
    let erro = cliente(&url).numbering().expect_err("deveria falhar");
    match erro {
        Error::Api { message, .. } => assert_eq!(message, "boom"),
        outro => panic!("esperava Error::Api, veio {outro:?}"),
    }
}

#[test]
fn servico_fora_do_ar_e_reportado_como_inalcancavel() {
    // Porta fechada: ninguém escutando.
    let listener = TcpListener::bind("127.0.0.1:0").expect("bind");
    let addr = listener.local_addr().expect("addr");
    drop(listener);
    let erro = cliente(&format!("http://{addr}"))
        .numbering()
        .expect_err("deveria falhar");
    assert!(matches!(erro, Error::Unreachable { .. }));
    assert_eq!(codigo_do_erro(&erro), EXIT_INDISPONIVEL);
}

#[test]
fn danfse_volta_como_bytes() {
    let url = servidor(200, "OK", "application/pdf", "%PDF-1.4 conteudo");
    let pdf = cliente(&url).danfse_by_chave("123").expect("danfse");
    assert!(pdf.starts_with(b"%PDF"));
}

#[test]
fn barra_final_na_url_nao_duplica() {
    let url = servidor(200, "OK", "application/json", r#"{"1":18}"#);
    let com_barra = format!("{url}/");
    let resposta = cliente(&com_barra).numbering().expect("numbering");
    assert_eq!(resposta["1"], 18);
}

// ----------------------------------------------------------------- argumentos

#[test]
fn erro_de_uso_nao_se_confunde_com_recusa() {
    use clap::error::ErrorKind;
    // clap sai com 2 por padrão, e 2 aqui é "documento recusado": um argumento
    // errado não pode ser lido por um script como nota rejeitada.
    assert_eq!(
        codigo_do_erro_de_uso(ErrorKind::InvalidSubcommand),
        EXIT_ERRO
    );
    assert_eq!(
        codigo_do_erro_de_uso(ErrorKind::MissingRequiredArgument),
        EXIT_ERRO
    );
    assert_eq!(codigo_do_erro_de_uso(ErrorKind::DisplayHelp), EXIT_OK);
    assert_eq!(codigo_do_erro_de_uso(ErrorKind::DisplayVersion), EXIT_OK);
}

#[test]
fn a_linha_de_comando_esta_bem_formada() {
    use clap::CommandFactory;
    Cli::command().debug_assert();
}
