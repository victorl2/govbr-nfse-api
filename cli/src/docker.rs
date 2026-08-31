//! Sobe o serviço em contêiner quando ele não está no ar.
//!
//! O serviço de UAT e o de produção diferem por uma única variável, o
//! `NFSE_PROFILE`, que é quem decide o `tpAmb`. Montar essa linha de comando à
//! mão toda vez é exatamente o tipo de coisa que um dia sai errada, e sair
//! errada aqui significa emitir no ambiente trocado. Guardar isso no config e
//! deixar a CLI subir o contêiner tira a chance de errar.

use std::process::{Command, Stdio};
use std::time::{Duration, Instant};

use crate::client::Error;
use crate::config::Docker;

/// Nome do contêiner já em execução, se houver.
pub fn em_execucao(container: &str) -> bool {
    saida(Command::new("docker").args([
        "ps",
        "--quiet",
        "--filter",
        &format!("name=^{container}$"),
    ]))
    .map(|s| !s.trim().is_empty())
    .unwrap_or(false)
}

/// Existe mas está parado — precisa de `rm` antes de subir de novo.
fn existe_parado(container: &str) -> bool {
    saida(Command::new("docker").args([
        "ps",
        "--all",
        "--quiet",
        "--filter",
        &format!("name=^{container}$"),
    ]))
    .map(|s| !s.trim().is_empty())
    .unwrap_or(false)
        && !em_execucao(container)
}

/// Os argumentos do `docker run`. Separado para poder ser testado sem Docker.
pub fn argumentos(d: &Docker, senha: Option<&str>) -> Vec<String> {
    let mut args: Vec<String> = vec![
        "run".into(),
        "--detach".into(),
        "--name".into(),
        d.container.clone(),
        "--memory".into(),
        d.memoria.clone(),
        "--publish".into(),
        format!("{}:8080", d.porta),
        // O certificado entra somente-leitura, e o entrypoint cuida da permissão.
        "--volume".into(),
        format!("{}:/secure/ecnpj.p12:ro", d.certificado),
        "--volume".into(),
        format!("{}:/var/lib/nfse", d.dados),
        "--env".into(),
        format!("NFSE_PROFILE={}", d.profile),
        "--env".into(),
        "NFSE_CERT_PATH=/secure/ecnpj.p12".into(),
    ];
    if let Some(senha) = senha {
        args.push("--env".into());
        args.push(format!("NFSE_CERT_PASSWORD={senha}"));
    }
    args.push(d.imagem.clone());
    args
}

/// Sobe o contêiner e espera ele responder. Devolve Ok(false) se já estava no ar.
pub fn subir(d: &Docker, url_health: &str) -> Result<bool, Error> {
    if em_execucao(&d.container) {
        return Ok(false);
    }
    if existe_parado(&d.container) {
        // Um contêiner parado com o mesmo nome faz o `run` falhar por conflito.
        let _ = Command::new("docker")
            .args(["rm", "--force", &d.container])
            .stdout(Stdio::null())
            .stderr(Stdio::null())
            .status();
    }

    let senha = match &d.senha_comando {
        Some(cmd) => Some(executar_para_senha(cmd)?),
        None => None,
    };

    eprintln!(
        "subindo {} ({}), perfil {} na porta {}…",
        d.container, d.imagem, d.profile, d.porta
    );
    let saida_run = Command::new("docker")
        .args(argumentos(d, senha.as_deref()))
        .output()
        .map_err(|e| Error::Local(format!("não foi possível executar o docker: {e}")))?;
    if !saida_run.status.success() {
        return Err(Error::Local(format!(
            "docker run falhou: {}",
            String::from_utf8_lossy(&saida_run.stderr).trim()
        )));
    }

    esperar(url_health, &d.container)?;
    Ok(true)
}

/// Baixa a imagem. Uma tag móvel como `:latest` fica velha em silêncio, e um
/// contêiner rodando código antigo é difícil de perceber: ele responde normal e
/// só falha no campo que mudou.
pub fn baixar(imagem: &str) -> Result<(), Error> {
    eprintln!("baixando {imagem}…");
    let saida_pull = Command::new("docker")
        .args(["pull", imagem])
        .output()
        .map_err(|e| Error::Local(format!("não foi possível executar o docker: {e}")))?;
    if !saida_pull.status.success() {
        return Err(Error::Local(format!(
            "docker pull falhou: {}",
            String::from_utf8_lossy(&saida_pull.stderr).trim()
        )));
    }
    Ok(())
}

pub fn descer(container: &str) -> Result<bool, Error> {
    if !em_execucao(container) && !existe_parado(container) {
        return Ok(false);
    }
    let saida_rm = Command::new("docker")
        .args(["rm", "--force", container])
        .output()
        .map_err(|e| Error::Local(format!("não foi possível executar o docker: {e}")))?;
    if !saida_rm.status.success() {
        return Err(Error::Local(format!(
            "docker rm falhou: {}",
            String::from_utf8_lossy(&saida_rm.stderr).trim()
        )));
    }
    Ok(true)
}

/// Espera o /health responder. Se o contêiner morrer no caminho, mostra o log
/// dele: um certificado ilegível falha aqui, e o motivo está no log, não no
/// tempo esgotado.
fn esperar(url_health: &str, container: &str) -> Result<(), Error> {
    let limite = Instant::now() + Duration::from_secs(60);
    let agente = ureq::AgentBuilder::new()
        .timeout(Duration::from_secs(2))
        .build();
    while Instant::now() < limite {
        if agente.get(url_health).call().is_ok() {
            return Ok(());
        }
        if !em_execucao(container) {
            let log = saida(Command::new("docker").args(["logs", "--tail", "20", container]))
                .unwrap_or_default();
            return Err(Error::Local(format!(
                "o contêiner {container} subiu e morreu. Últimas linhas:\n{log}"
            )));
        }
        std::thread::sleep(Duration::from_millis(200));
    }
    Err(Error::Local(format!(
        "o contêiner {container} não respondeu em 60s"
    )))
}

/// Roda o comando que devolve a senha. A saída é tratada como segredo: nunca é
/// impressa, nem em caso de erro.
fn executar_para_senha(comando: &str) -> Result<String, Error> {
    let saida_cmd = Command::new("sh")
        .arg("-c")
        .arg(comando)
        .output()
        .map_err(|e| Error::Local(format!("não foi possível rodar o comando da senha: {e}")))?;
    if !saida_cmd.status.success() {
        return Err(Error::Local(
            "o comando da senha do certificado falhou (a saída não é mostrada por ser segredo)"
                .into(),
        ));
    }
    let senha = String::from_utf8_lossy(&saida_cmd.stdout)
        .trim()
        .to_string();
    if senha.is_empty() {
        return Err(Error::Local(
            "o comando da senha do certificado não devolveu nada".into(),
        ));
    }
    Ok(senha)
}

fn saida(cmd: &mut Command) -> Option<String> {
    let out = cmd.stderr(Stdio::null()).output().ok()?;
    out.status
        .success()
        .then(|| String::from_utf8_lossy(&out.stdout).to_string())
}
