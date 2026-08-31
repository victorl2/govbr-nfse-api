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
    for status in ["REJECTED_BY_SEFIN", "REJECTED_LOCALLY"] {
        let v = serde_json::json!({ "status": status });
        assert_eq!(codigo_do_status(&v), EXIT_RECUSADO, "status {status}");
    }
}

#[test]
fn falha_em_transito_nao_e_recusa() {
    // A diferença que evita nota duplicada: recusa é problema do documento,
    // SUBMIT_FAILED é não saber se a nota foi criada.
    let v = serde_json::json!({"status": "SUBMIT_FAILED"});
    assert_eq!(codigo_do_status(&v), EXIT_INDETERMINADO);
    assert_ne!(codigo_do_status(&v), EXIT_RECUSADO);
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

// -------------------------------------------------------------------- config

use std::sync::atomic::{AtomicUsize, Ordering};

/// Caminho novo a cada chamada: os testes rodam em paralelo e não podem
/// disputar o mesmo arquivo.
fn caminho_temporario() -> std::path::PathBuf {
    static N: AtomicUsize = AtomicUsize::new(0);
    let n = N.fetch_add(1, Ordering::Relaxed);
    std::env::temp_dir()
        .join(format!("nfse-cli-teste-{}-{n}", std::process::id()))
        .join(".nfse")
        .join("config.json")
}

#[test]
fn config_inexistente_e_criado_com_os_padroes() {
    let caminho = caminho_temporario();
    assert!(!caminho.exists());
    let config = Config::carregar(Some(&caminho)).expect("carregar");
    assert!(config.recem_criado);
    assert!(caminho.exists(), "o arquivo deveria ter sido criado");
    assert_eq!(config.dados.ambiente_ativo, "local");
    assert_eq!(config.url_ativa(), config::URL_PADRAO);
    let _ = std::fs::remove_dir_all(caminho.parent().unwrap().parent().unwrap());
}

#[test]
fn ambiente_e_modelo_sobrevivem_a_releitura() {
    let caminho = caminho_temporario();
    {
        let mut config = Config::carregar(Some(&caminho)).expect("criar");
        config.dados.ambientes.insert(
            "restrita".into(),
            config::Ambiente {
                url: "https://nfse.interno:8443".into(),
                docker: None,
            },
        );
        config.dados.ambiente_ativo = "restrita".into();
        config.dados.modelos.insert(
            "mensal".into(),
            serde_json::json!({"values":{"vServ":"10000.00"}}),
        );
        config.dados.modelo_padrao = Some("mensal".into());
        config.salvar().expect("salvar");
    }
    let config = Config::carregar(Some(&caminho)).expect("reler");
    assert!(!config.recem_criado);
    assert_eq!(config.url_ativa(), "https://nfse.interno:8443");
    assert_eq!(config.dados.modelo_padrao.as_deref(), Some("mensal"));
    assert_eq!(
        config.modelo("mensal").expect("modelo")["values"]["vServ"],
        "10000.00"
    );
    let _ = std::fs::remove_dir_all(caminho.parent().unwrap().parent().unwrap());
}

#[test]
fn modelo_inexistente_diz_quais_existem() {
    let caminho = caminho_temporario();
    let mut config = Config::carregar(Some(&caminho)).expect("criar");
    config
        .dados
        .modelos
        .insert("mensal".into(), serde_json::json!({}));
    let erro = config.modelo("anual").expect_err("deveria falhar");
    let texto = erro.to_string();
    assert!(texto.contains("anual"), "{texto}");
    assert!(texto.contains("mensal"), "{texto}");
    let _ = std::fs::remove_dir_all(caminho.parent().unwrap().parent().unwrap());
}

#[test]
fn config_corrompido_explica_como_recuperar() {
    let caminho = caminho_temporario();
    std::fs::create_dir_all(caminho.parent().unwrap()).unwrap();
    std::fs::write(&caminho, "{ isto não é json").unwrap();
    let erro = Config::carregar(Some(&caminho)).expect_err("deveria falhar");
    assert!(erro.to_string().contains("apague o arquivo"));
    let _ = std::fs::remove_dir_all(caminho.parent().unwrap().parent().unwrap());
}

// ------------------------------------------------- substituições no modelo

#[test]
fn substituicao_altera_apenas_o_campo_pedido() {
    // O caso do dia a dia: o modelo tem a venda inteira e a emissão muda o valor.
    let mut venda = serde_json::json!({
        "emitter": {"cnpj": "12345678000195"},
        "dps": {"serie": "70000", "dCompet": "2026-01-31"},
        "values": {"vServ": "1000.00", "tribISSQN": "3"}
    });
    config::definir(
        &mut venda,
        &["values", "vServ"],
        serde_json::json!("2500.00"),
    );
    config::definir(
        &mut venda,
        &["dps", "dCompet"],
        serde_json::json!("2026-08-31"),
    );

    assert_eq!(venda["values"]["vServ"], "2500.00");
    assert_eq!(venda["dps"]["dCompet"], "2026-08-31");
    // O resto do modelo fica intacto.
    assert_eq!(venda["values"]["tribISSQN"], "3");
    assert_eq!(venda["dps"]["serie"], "70000");
    assert_eq!(venda["emitter"]["cnpj"], "12345678000195");
}

#[test]
fn exportacao_troca_as_duas_moedas() {
    // Numa nota de exportação o valor existe em dois lugares: reais em
    // values.vServ e moeda estrangeira em comercioExterior.vServMoeda. Trocar só
    // um deixaria a nota internamente incoerente.
    let mut venda = serde_json::json!({
        "values": {"vServ": "43002.73", "cPaisResult": "CA"},
        "comercioExterior": {"vServMoeda": "8480.00", "tpMoeda": "220"}
    });
    config::definir(
        &mut venda,
        &["values", "vServ"],
        serde_json::json!("45000.00"),
    );
    config::definir(
        &mut venda,
        &["comercioExterior", "vServMoeda"],
        serde_json::json!("8900.00"),
    );
    assert_eq!(venda["values"]["vServ"], "45000.00");
    assert_eq!(venda["comercioExterior"]["vServMoeda"], "8900.00");
    // o resto do modelo continua de pé
    assert_eq!(venda["values"]["cPaisResult"], "CA");
    assert_eq!(venda["comercioExterior"]["tpMoeda"], "220");
}

#[test]
fn substituicao_cria_os_niveis_que_faltam() {
    let mut venda = serde_json::json!({});
    config::definir(
        &mut venda,
        &["service", "description"],
        serde_json::json!("Consultoria"),
    );
    assert_eq!(venda["service"]["description"], "Consultoria");
}

// ----------------------------------------------------------- confirmação

#[test]
fn so_um_sim_explicito_emite() {
    for ok in ["s", "S", "sim", "SIM", "y", "yes", " s \n"] {
        assert!(resposta_positiva(ok), "deveria aceitar {ok:?}");
    }
    // Enter vazio é a resposta mais provável de quem hesitou: não pode emitir.
    for nao in ["", "\n", "n", "não", "nao", "no", "talvez", "x"] {
        assert!(!resposta_positiva(nao), "não deveria aceitar {nao:?}");
    }
}

#[test]
#[cfg(unix)]
fn o_config_nasce_fechado() {
    use std::os::unix::fs::PermissionsExt;
    // O config guarda os modelos, e um modelo é a venda inteira: CNPJ, contato
    // e dados do tomador. Não pode nascer legível por todo mundo.
    let caminho = caminho_temporario();
    let config = Config::carregar(Some(&caminho)).expect("criar");
    let arquivo = std::fs::metadata(&caminho)
        .expect("stat")
        .permissions()
        .mode()
        & 0o777;
    let pasta = std::fs::metadata(caminho.parent().unwrap())
        .expect("stat")
        .permissions()
        .mode()
        & 0o777;
    assert_eq!(arquivo, 0o600, "config deveria ser 0600, é {arquivo:o}");
    assert_eq!(pasta, 0o700, "pasta deveria ser 0700, é {pasta:o}");
    drop(config);
    let _ = std::fs::remove_dir_all(caminho.parent().unwrap().parent().unwrap());
}

// ---------------------------------------------------------------- contêiner

fn docker_exemplo(profile: &str, porta: u16) -> config::Docker {
    config::Docker {
        imagem: "ghcr.io/victorl2/govbr-nfse-api:latest".into(),
        container: format!("nfse-{profile}"),
        porta,
        profile: profile.into(),
        certificado: "/Users/victor/.nfse/ecnpj.p12".into(),
        dados: "/Users/victor/.nfse/data".into(),
        senha_comando: None,
        memoria: "192m".into(),
    }
}

#[test]
fn o_profile_e_a_porta_chegam_ao_conteiner() {
    // É o NFSE_PROFILE que decide o tpAmb. Trocá-lo aqui é emitir no ambiente
    // errado, então vale prender exatamente o que vai na linha de comando.
    let args = docker::argumentos(&docker_exemplo("producao", 8081), None);
    let linha = args.join(" ");
    assert!(linha.contains("NFSE_PROFILE=producao"), "{linha}");
    assert!(linha.contains("8081:8080"), "{linha}");
    assert!(linha.contains("--detach"), "{linha}");
    assert!(
        linha.contains("/Users/victor/.nfse/data:/var/lib/nfse"),
        "{linha}"
    );
    // O certificado entra somente-leitura.
    assert!(linha.contains("ecnpj.p12:ro"), "{linha}");
}

#[test]
fn uat_e_producao_nao_se_confundem() {
    let uat = docker::argumentos(&docker_exemplo("restrita", 8080), None).join(" ");
    let prod = docker::argumentos(&docker_exemplo("producao", 8081), None).join(" ");
    assert!(uat.contains("NFSE_PROFILE=restrita") && !uat.contains("producao"));
    assert!(prod.contains("NFSE_PROFILE=producao") && !prod.contains("restrita"));
    // Nomes de contêiner distintos, senão um substitui o outro.
    assert!(uat.contains("nfse-restrita") && prod.contains("nfse-producao"));
}

#[test]
fn a_senha_so_entra_quando_existe() {
    let sem = docker::argumentos(&docker_exemplo("restrita", 8080), None).join(" ");
    assert!(
        !sem.contains("NFSE_CERT_PASSWORD"),
        "senha vazia não deve virar env"
    );

    let com = docker::argumentos(&docker_exemplo("restrita", 8080), Some("segredo")).join(" ");
    assert!(com.contains("NFSE_CERT_PASSWORD=segredo"));
}

#[test]
fn a_senha_nunca_e_gravada_no_config() {
    // O config guarda o COMANDO que devolve a senha, nunca a senha.
    let mut d = docker_exemplo("producao", 8081);
    d.senha_comando = Some("security find-generic-password -a nfse -s nfse-cert -w".into());
    let json = serde_json::to_value(&d).expect("serializar");
    let campos = json.as_object().expect("objeto");
    // Existe o campo do comando...
    assert!(campos.contains_key("senhaComando"));
    // ...e não existe campo nenhum onde a senha em si pudesse ser guardada.
    // (o comando do Keychain contém a palavra "password", então procurar pela
    // palavra no texto não provaria nada — o que importa é o formato.)
    for proibido in ["senha", "password", "certPassword", "certificadoSenha"] {
        assert!(
            !campos.contains_key(proibido),
            "config não pode ter '{proibido}'"
        );
    }
}

// -------------------------------------------------------------------- datas

#[test]
fn competencia_aceita_o_formato_brasileiro() {
    assert_eq!(datas::data_para_iso("31/08/2026").unwrap(), "2026-08-31");
    assert_eq!(datas::data_para_iso("07/08/2026").unwrap(), "2026-08-07");
    // Dia e mês com um dígito só continuam valendo.
    assert_eq!(datas::data_para_iso("7/8/2026").unwrap(), "2026-08-07");
}

#[test]
fn competencia_em_iso_continua_valendo() {
    // Os modelos guardam ISO; aceitar só o formato novo quebraria todos eles.
    assert_eq!(datas::data_para_iso("2026-08-31").unwrap(), "2026-08-31");
}

#[test]
fn data_sem_sentido_e_recusada_antes_de_sair() {
    for ruim in [
        "31-08-2026",
        "2026/08/31",
        "08/2026",
        "hoje",
        "32/08/2026",
        "31/13/2026",
        "",
    ] {
        assert!(
            datas::data_para_iso(ruim).is_err(),
            "deveria recusar {ruim:?}"
        );
    }
}

#[test]
fn dia_e_mes_nao_sao_adivinhados() {
    // 03/04/2026 é 3 de abril. Um parser que decidisse sozinho entre dia/mês e
    // mês/dia erraria a competência de uma nota fiscal sem avisar.
    assert_eq!(datas::data_para_iso("03/04/2026").unwrap(), "2026-04-03");
}

#[test]
fn emissao_ganha_hora_e_fuso_de_brasilia() {
    assert_eq!(
        datas::datahora_para_iso("31/08/2026").unwrap(),
        "2026-08-31T00:00:00-03:00"
    );
    assert_eq!(
        datas::datahora_para_iso("31/08/2026 14:30").unwrap(),
        "2026-08-31T14:30:00-03:00"
    );
    assert_eq!(
        datas::datahora_para_iso("31/08/2026 14:30:59").unwrap(),
        "2026-08-31T14:30:59-03:00"
    );
}

#[test]
fn emissao_com_fuso_explicito_e_respeitada() {
    // Um ISO completo passa intacto: quem informou o fuso sabe o que quer.
    let iso = "2026-07-31T21:26:57-03:00";
    assert_eq!(datas::datahora_para_iso(iso).unwrap(), iso);
}

#[test]
fn exibicao_volta_para_o_formato_brasileiro() {
    assert_eq!(datas::para_br("2026-08-31"), "31/08/2026");
    assert_eq!(
        datas::para_br("2026-08-31T14:30:59-03:00"),
        "31/08/2026 14:30"
    );
    // O que não for data volta como veio: um resumo é para ler, não para falhar.
    assert_eq!(datas::para_br("-"), "-");
    assert_eq!(datas::para_br("qualquer coisa"), "qualquer coisa");
}

#[test]
fn ida_e_volta_preserva_a_data() {
    for br in ["31/08/2026", "01/01/2026", "29/02/2028"] {
        let iso = datas::data_para_iso(br).unwrap();
        assert_eq!(datas::para_br(&iso), br, "ida e volta mudou {br}");
    }
}
