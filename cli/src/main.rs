//! CLI para o serviço de emissão de NFS-e.
//!
//! O serviço faz todo o trabalho fiscal; esta CLI é uma casca sobre a API HTTP.
//! Os códigos de saída são o contrato importante aqui, porque emissão costuma
//! rodar dentro de script: 0 sucesso, 2 documento recusado, 3 serviço fora,
//! 4 não encontrado, 1 qualquer outro erro.

mod client;

use std::path::PathBuf;
use std::process::ExitCode;
use std::time::Duration;

use clap::{Parser, Subcommand};
use serde_json::Value;

use client::{Client, Error};

const EXIT_OK: u8 = 0;
const EXIT_ERRO: u8 = 1;
const EXIT_RECUSADO: u8 = 2;
const EXIT_INDISPONIVEL: u8 = 3;
const EXIT_NAO_ENCONTRADO: u8 = 4;

#[derive(Parser)]
#[command(
    name = "nfse",
    version,
    about = "CLI do emissor de NFS-e (Sistema Nacional)",
    long_about = "Conversa com o serviço de emissão de NFS-e pela API HTTP.\n\n\
                  Códigos de saída: 0 sucesso, 1 erro, 2 documento recusado,\n\
                  3 serviço indisponível, 4 não encontrado."
)]
struct Cli {
    /// Endereço do serviço
    #[arg(
        long,
        global = true,
        env = "NFSE_URL",
        default_value = "http://localhost:8080"
    )]
    url: String,

    /// Tempo limite de cada requisição, em segundos
    #[arg(long, global = true, env = "NFSE_TIMEOUT", default_value_t = 60)]
    timeout: u64,

    /// Imprime a resposta JSON crua, sem resumo
    #[arg(long, global = true)]
    json: bool,

    #[command(subcommand)]
    comando: Comando,
}

#[derive(Subcommand)]
enum Comando {
    /// Estado do serviço e do certificado
    Health,
    /// Dados do certificado carregado
    Certificado,
    /// Testa a conectividade mTLS com a SEFIN
    Conectividade,

    /// Monta, valida e assina a DPS sem transmitir nada
    Validar {
        /// Arquivo JSON descrevendo a venda
        #[arg(short, long)]
        arquivo: PathBuf,
    },
    /// Emite a NFS-e: monta, assina e transmite a DPS à SEFIN
    Emitir {
        /// Arquivo JSON descrevendo a venda
        #[arg(short, long)]
        arquivo: PathBuf,
        /// Grava o XML da NFS-e autorizada neste caminho
        #[arg(long)]
        salvar_xml: Option<PathBuf>,
    },

    /// Registro local de uma nota emitida
    Consultar { chave: String },
    /// XML da NFS-e autorizada, como foi arquivado
    Xml {
        chave: String,
        /// Grava em arquivo em vez de imprimir
        #[arg(short, long)]
        saida: Option<PathBuf>,
    },
    /// Gera a DANFSe em PDF
    Danfse {
        /// Chave de acesso — busca a nota na SEFIN
        chave: Option<String>,
        /// XML de NFS-e que você já tem, em vez da chave
        #[arg(short, long, conflicts_with = "chave")]
        arquivo: Option<PathBuf>,
        /// Caminho do PDF gerado
        #[arg(short, long, default_value = "danfse.pdf")]
        saida: PathBuf,
    },

    /// Cancela uma NFS-e emitida
    Cancelar {
        chave: String,
        /// 1 = Erro na Emissão, 2 = Serviço não Prestado, 9 = Outros
        #[arg(short, long)]
        motivo: String,
        /// Justificativa, mínimo 15 caracteres (exigência do XSD oficial)
        #[arg(short, long)]
        justificativa: String,
        /// CNPJ do autor do evento
        #[arg(long)]
        autor: Option<String>,
        /// Só valida o pedido, sem transmitir
        #[arg(long)]
        simular: bool,
    },
    /// Lê um evento registrado
    Evento {
        chave: String,
        /// Código do evento, ex. 101101 (cancelamento)
        #[arg(short, long)]
        tipo: String,
        /// Número sequencial do evento
        #[arg(short, long, default_value_t = 1)]
        sequencia: u32,
        #[arg(short = 'o', long)]
        saida: Option<PathBuf>,
    },

    /// Tentativas de emissão recentes, da mais nova para a mais antiga
    Emissoes {
        #[arg(short, long, default_value_t = 20)]
        limite: u32,
    },
    /// Consulta ou semeia os contadores de numeração
    Numeracao {
        /// Série a semear
        #[arg(long, requires = "ultimo_consumido")]
        serie: Option<String>,
        /// Último número já consumido nessa série
        #[arg(long, requires = "serie")]
        ultimo_consumido: Option<u64>,
    },

    /// Valida uma DPS em XML que você já montou
    ValidarDps {
        #[arg(short, long)]
        arquivo: PathBuf,
        /// Código IBGE do município esperado
        #[arg(long)]
        municipio: Option<String>,
        /// 1 = Não Optante, 2 = MEI, 3 = ME/EPP
        #[arg(long)]
        opsimpnac: Option<String>,
    },
}

fn main() -> ExitCode {
    // Não usamos `Cli::parse()`: ele encerra com 2 em erro de uso, e 2 aqui
    // significa "documento recusado". Confundir um argumento errado com uma
    // recusa da SEFIN faria um script tratar um typo como problema fiscal.
    let cli = match Cli::try_parse() {
        Ok(cli) => cli,
        Err(e) => {
            let _ = e.print();
            return ExitCode::from(codigo_do_erro_de_uso(e.kind()));
        }
    };
    let client = Client::new(&cli.url, Duration::from_secs(cli.timeout));

    match executar(&cli, &client) {
        Ok(code) => ExitCode::from(code),
        Err(e) => {
            eprintln!("erro: {e}");
            ExitCode::from(codigo_do_erro(&e))
        }
    }
}

/// `--help` e `--version` não são falhas; qualquer outro problema de linha de
/// comando é erro de uso, nunca recusa de documento.
fn codigo_do_erro_de_uso(kind: clap::error::ErrorKind) -> u8 {
    use clap::error::ErrorKind;
    match kind {
        ErrorKind::DisplayHelp
        | ErrorKind::DisplayVersion
        | ErrorKind::DisplayHelpOnMissingArgumentOrSubcommand => EXIT_OK,
        _ => EXIT_ERRO,
    }
}

/// 404 e 503/529 viram códigos próprios: um script que chama isto precisa
/// distinguir "a nota não existe" de "o serviço está fora" de "deu ruim".
fn codigo_do_erro(e: &Error) -> u8 {
    match e {
        Error::Api { status: 404, .. } => EXIT_NAO_ENCONTRADO,
        Error::Api { status: 503, .. } | Error::Api { status: 529, .. } => EXIT_INDISPONIVEL,
        Error::Unreachable { .. } => EXIT_INDISPONIVEL,
        _ => EXIT_ERRO,
    }
}

fn executar(cli: &Cli, client: &Client) -> client::Result<u8> {
    match &cli.comando {
        Comando::Health => {
            let (status, corpo) = client.health()?;
            if cli.json {
                imprimir_json(&corpo);
            } else {
                resumir_health(&corpo);
            }
            Ok(if status == 200 {
                EXIT_OK
            } else {
                EXIT_INDISPONIVEL
            })
        }

        Comando::Certificado => {
            imprimir_json(&client.certificate()?);
            Ok(EXIT_OK)
        }

        Comando::Conectividade => {
            imprimir_json(&client.connectivity()?);
            Ok(EXIT_OK)
        }

        Comando::Validar { arquivo } => {
            let resposta = client.validate(ler_json(arquivo)?)?;
            if cli.json {
                imprimir_json(&resposta);
            } else {
                let valido = resposta
                    .get("valid")
                    .and_then(Value::as_bool)
                    .unwrap_or(false);
                println!(
                    "{}",
                    if valido {
                        "DPS válida"
                    } else {
                        "DPS inválida"
                    }
                );
                imprimir_achados(&resposta);
            }
            Ok(
                if resposta
                    .get("valid")
                    .and_then(Value::as_bool)
                    .unwrap_or(false)
                {
                    EXIT_OK
                } else {
                    EXIT_RECUSADO
                },
            )
        }

        Comando::Emitir {
            arquivo,
            salvar_xml,
        } => {
            let resposta = client.send(ler_json(arquivo)?)?;
            if let Some(caminho) = salvar_xml {
                if let Some(xml) = resposta.get("nfseXml").and_then(Value::as_str) {
                    escrever(caminho, xml.as_bytes())?;
                    eprintln!("XML da NFS-e gravado em {}", caminho.display());
                } else {
                    eprintln!("aviso: a resposta não trouxe nfseXml, nada foi gravado");
                }
            }
            if cli.json {
                imprimir_json(&resposta);
            } else {
                resumir_emissao(&resposta);
            }
            Ok(codigo_do_status(&resposta))
        }

        Comando::Consultar { chave } => {
            imprimir_json(&client.note(chave)?);
            Ok(EXIT_OK)
        }

        Comando::Xml { chave, saida } => {
            let xml = client.note_xml(chave)?;
            match saida {
                Some(caminho) => {
                    escrever(caminho, &xml)?;
                    eprintln!("gravado em {}", caminho.display());
                }
                None => print!("{}", String::from_utf8_lossy(&xml)),
            }
            Ok(EXIT_OK)
        }

        Comando::Danfse {
            chave,
            arquivo,
            saida,
        } => {
            let pdf = match (chave, arquivo) {
                (Some(chave), _) => client.danfse_by_chave(chave)?,
                (None, Some(caminho)) => client.danfse_from_xml(&ler_bytes(caminho)?)?,
                (None, None) => {
                    return Err(Error::Local(
                        "informe a chave de acesso ou --arquivo com o XML da NFS-e".into(),
                    ))
                }
            };
            escrever(saida, &pdf)?;
            eprintln!(
                "DANFSe gravada em {} ({} bytes)",
                saida.display(),
                pdf.len()
            );
            Ok(EXIT_OK)
        }

        Comando::Cancelar {
            chave,
            motivo,
            justificativa,
            autor,
            simular,
        } => {
            // O XSD oficial exige 15 caracteres. Barrar aqui evita uma ida à
            // SEFIN para receber a mesma recusa.
            if justificativa.chars().count() < 15 {
                return Err(Error::Local(format!(
                    "a justificativa precisa de ao menos 15 caracteres (tem {})",
                    justificativa.chars().count()
                )));
            }
            let mut corpo = serde_json::Map::new();
            corpo.insert("cMotivo".into(), Value::String(motivo.clone()));
            corpo.insert("xMotivo".into(), Value::String(justificativa.clone()));
            if let Some(cnpj) = autor {
                corpo.insert("cnpjAutor".into(), Value::String(cnpj.clone()));
            }
            let resposta = client.cancel(chave, Value::Object(corpo), *simular)?;
            if cli.json {
                imprimir_json(&resposta);
            } else {
                resumir_evento(&resposta);
            }
            Ok(codigo_do_status(&resposta))
        }

        Comando::Evento {
            chave,
            tipo,
            sequencia,
            saida,
        } => {
            let xml = client.event(chave, tipo, *sequencia)?;
            match saida {
                Some(caminho) => {
                    escrever(caminho, &xml)?;
                    eprintln!("gravado em {}", caminho.display());
                }
                None => print!("{}", String::from_utf8_lossy(&xml)),
            }
            Ok(EXIT_OK)
        }

        Comando::Emissoes { limite } => {
            let resposta = client.emissions(*limite)?;
            if cli.json {
                imprimir_json(&resposta);
            } else {
                resumir_emissoes(&resposta);
            }
            Ok(EXIT_OK)
        }

        Comando::Numeracao {
            serie,
            ultimo_consumido,
        } => {
            let resposta = match (serie, ultimo_consumido) {
                (Some(s), Some(n)) => client.seed_numbering(s, *n)?,
                _ => client.numbering()?,
            };
            imprimir_json(&resposta);
            Ok(EXIT_OK)
        }

        Comando::ValidarDps {
            arquivo,
            municipio,
            opsimpnac,
        } => {
            let resposta = client.dry_run(
                &ler_bytes(arquivo)?,
                municipio.as_deref(),
                opsimpnac.as_deref(),
            )?;
            if cli.json {
                imprimir_json(&resposta);
            } else {
                imprimir_achados(&resposta);
            }
            Ok(EXIT_OK)
        }
    }
}

// --------------------------------------------------------------- apresentação

fn codigo_do_status(resposta: &Value) -> u8 {
    match resposta.get("status").and_then(Value::as_str) {
        Some("AUTHORIZED") | Some("REGISTERED") => EXIT_OK,
        Some(_) => EXIT_RECUSADO,
        None => EXIT_OK,
    }
}

fn imprimir_json(v: &Value) {
    match serde_json::to_string_pretty(v) {
        Ok(s) => println!("{s}"),
        Err(_) => println!("{v}"),
    }
}

fn resumir_health(corpo: &Value) {
    let status = corpo.get("status").and_then(Value::as_str).unwrap_or("?");
    println!("status: {status}");
    if let Some(cert) = corpo.get("certificate") {
        let dias = cert.get("daysToExpiry").and_then(Value::as_i64);
        let ate = cert.get("notAfter").and_then(Value::as_str).unwrap_or("?");
        match dias {
            Some(d) => println!("certificado: vence em {d} dia(s), em {ate}"),
            None => println!("certificado: {ate}"),
        }
    }
    if let Some(avisos) = corpo.get("warnings").and_then(Value::as_array) {
        for aviso in avisos {
            if let Some(texto) = aviso.as_str() {
                println!("aviso: {texto}");
            }
        }
    }
}

fn resumir_emissao(resposta: &Value) {
    let status = resposta
        .get("status")
        .and_then(Value::as_str)
        .unwrap_or("?");
    println!("status: {status}");
    if let Some(chave) = resposta.get("chaveAcesso").and_then(Value::as_str) {
        println!("chave de acesso: {chave}");
    }
    imprimir_achados(resposta);
}

fn resumir_evento(resposta: &Value) {
    let status = resposta
        .get("status")
        .and_then(Value::as_str)
        .unwrap_or("?");
    println!("status: {status}");
    imprimir_achados(resposta);
}

fn resumir_emissoes(resposta: &Value) {
    let Some(itens) = resposta.as_array() else {
        imprimir_json(resposta);
        return;
    };
    if itens.is_empty() {
        println!("nenhuma emissão registrada");
        return;
    }
    for item in itens {
        let quando = item.get("createdAt").and_then(Value::as_str).unwrap_or("");
        let status = item.get("status").and_then(Value::as_str).unwrap_or("?");
        let chave = item
            .get("chaveAcesso")
            .and_then(Value::as_str)
            .unwrap_or("-");
        println!("{quando}  {status:<18}  {chave}");
    }
}

/// Os achados são o produto mais útil do serviço: dizem exatamente qual regra
/// reprovou o documento e em que etapa.
fn imprimir_achados(resposta: &Value) {
    let Some(achados) = resposta.get("findings").and_then(Value::as_array) else {
        return;
    };
    if achados.is_empty() {
        return;
    }
    println!("achados:");
    for achado in achados {
        let severidade = achado
            .get("severity")
            .and_then(Value::as_str)
            .unwrap_or("INFO");
        let etapa = achado.get("stage").and_then(Value::as_str).unwrap_or("-");
        let codigo = achado.get("code").and_then(Value::as_str).unwrap_or("-");
        let mensagem = achado.get("message").and_then(Value::as_str).unwrap_or("");
        println!("  [{severidade}] {etapa} {codigo}: {mensagem}");
    }
}

// ------------------------------------------------------------------- arquivos

fn ler_bytes(caminho: &PathBuf) -> client::Result<Vec<u8>> {
    std::fs::read(caminho)
        .map_err(|e| Error::Local(format!("não foi possível ler {}: {e}", caminho.display())))
}

fn ler_json(caminho: &PathBuf) -> client::Result<Value> {
    let bytes = ler_bytes(caminho)?;
    serde_json::from_slice(&bytes)
        .map_err(|e| Error::Local(format!("{} não é um JSON válido: {e}", caminho.display())))
}

fn escrever(caminho: &PathBuf, dados: &[u8]) -> client::Result<()> {
    std::fs::write(caminho, dados).map_err(|e| {
        Error::Local(format!(
            "não foi possível gravar {}: {e}",
            caminho.display()
        ))
    })
}

#[cfg(test)]
mod tests;
