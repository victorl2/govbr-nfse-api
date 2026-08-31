//! CLI para o serviço de emissão de NFS-e.
//!
//! O serviço faz todo o trabalho fiscal; esta CLI é uma casca sobre a API HTTP.
//! Os códigos de saída são o contrato importante aqui, porque emissão costuma
//! rodar dentro de script: 0 sucesso, 2 documento recusado, 3 serviço fora,
//! 4 não encontrado, 5 estado indeterminado, 1 qualquer outro erro.

mod client;
mod config;

use std::path::PathBuf;
use std::process::ExitCode;
use std::time::Duration;

use clap::{Args, Parser, Subcommand};
use serde_json::Value;

use client::{Client, Error};
use config::Config;

const EXIT_OK: u8 = 0;
const EXIT_ERRO: u8 = 1;
const EXIT_RECUSADO: u8 = 2;
const EXIT_INDISPONIVEL: u8 = 3;
const EXIT_NAO_ENCONTRADO: u8 = 4;
const EXIT_INDETERMINADO: u8 = 5;

#[derive(Parser)]
#[command(
    name = "nfse",
    version,
    about = "CLI do emissor de NFS-e (Sistema Nacional)",
    long_about = "Conversa com o serviço de emissão de NFS-e pela API HTTP.\n\n\
                  Códigos de saída: 0 sucesso, 1 erro, 2 documento recusado,\n\
                  3 serviço indisponível, 4 não encontrado,\n\
                  5 indeterminado (não se sabe se a nota foi criada)."
)]
struct Cli {
    /// Endereço do serviço. Sem isto, vale o ambiente ativo do config
    #[arg(long, global = true, env = "NFSE_URL")]
    url: Option<String>,

    /// Caminho do config (padrão: ./.nfse-cli/config.json ou ~/.nfse-cli/config.json)
    #[arg(long, global = true, env = "NFSE_CLI_CONFIG")]
    config: Option<PathBuf>,

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
        #[command(flatten)]
        venda: EntradaVenda,
    },
    /// Emite a NFS-e: monta, assina e transmite a DPS à SEFIN
    Emitir {
        #[command(flatten)]
        venda: EntradaVenda,
        /// Grava o XML da NFS-e autorizada neste caminho
        #[arg(long)]
        salvar_xml: Option<PathBuf>,
    },

    /// Ambiente ativo e modelos de venda
    Config {
        #[command(subcommand)]
        acao: Option<AcaoConfig>,
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

/// De onde sai a venda e o que muda nela. O caso comum do dia a dia é um
/// modelo salvo mais o valor e a competência do mês.
#[derive(Args, Clone)]
struct EntradaVenda {
    /// Arquivo JSON descrevendo a venda
    #[arg(short, long, conflicts_with = "modelo")]
    arquivo: Option<PathBuf>,
    /// Modelo salvo no config
    #[arg(short = 'M', long)]
    modelo: Option<String>,
    /// Substitui values.vServ
    #[arg(long)]
    valor: Option<String>,
    /// Substitui dps.dCompet (AAAA-MM-DD)
    #[arg(long)]
    competencia: Option<String>,
    /// Substitui dps.dhEmi
    #[arg(long)]
    emissao: Option<String>,
    /// Substitui service.description
    #[arg(long)]
    descricao: Option<String>,
    /// Substitui dps.serie
    #[arg(long)]
    serie: Option<String>,
}

#[derive(Subcommand)]
enum AcaoConfig {
    /// Mostra o caminho e o conteúdo do config
    Mostrar,
    /// Ativa um ambiente e, com --url, define o endereço dele
    Ambiente {
        nome: String,
        #[arg(long)]
        url: Option<String>,
    },
    /// Modelos de venda
    Modelo {
        #[command(subcommand)]
        acao: AcaoModelo,
    },
}

#[derive(Subcommand)]
enum AcaoModelo {
    /// Guarda uma venda como modelo
    Salvar {
        nome: String,
        #[arg(short, long)]
        arquivo: PathBuf,
        /// Passa a ser o modelo usado quando nenhum for indicado
        #[arg(long)]
        padrao: bool,
    },
    /// Lista os modelos salvos
    Listar,
    /// Remove um modelo
    Remover { nome: String },
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
    let mut config = match Config::carregar(cli.config.as_deref()) {
        Ok(c) => c,
        Err(e) => {
            eprintln!("erro: {e}");
            return ExitCode::from(EXIT_ERRO);
        }
    };
    if config.recem_criado {
        eprintln!("config criado em {}", config.caminho.display());
    }

    // --url ganha do config; sem ele, vale o ambiente ativo.
    let url = cli
        .url
        .clone()
        .unwrap_or_else(|| config.url_ativa().to_string());
    let client = Client::new(&url, Duration::from_secs(cli.timeout));

    match executar(&cli, &client, &mut config) {
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

fn executar(cli: &Cli, client: &Client, config: &mut Config) -> client::Result<u8> {
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

        Comando::Validar { venda } => {
            let resposta = client.validate(montar_venda(venda, config)?)?;
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

        Comando::Emitir { venda, salvar_xml } => {
            let resposta = client.send(montar_venda(venda, config)?)?;
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

        Comando::Config { acao } => executar_config(acao.as_ref(), config, cli.json),

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

// ------------------------------------------------------------------- config

fn executar_config(
    acao: Option<&AcaoConfig>,
    config: &mut Config,
    json: bool,
) -> client::Result<u8> {
    match acao {
        None | Some(AcaoConfig::Mostrar) => {
            if json {
                imprimir_json(&serde_json::to_value(&config.dados).unwrap_or(Value::Null));
            } else {
                println!("config: {}", config.caminho.display());
                println!("ambiente ativo: {}", config.dados.ambiente_ativo);
                for (nome, ambiente) in &config.dados.ambientes {
                    let marca = if *nome == config.dados.ambiente_ativo {
                        "*"
                    } else {
                        " "
                    };
                    println!("  {marca} {nome}: {}", ambiente.url);
                }
                let padrao = config.dados.modelo_padrao.as_deref().unwrap_or("-");
                println!("modelo padrão: {padrao}");
                if config.dados.modelos.is_empty() {
                    println!("modelos: nenhum");
                } else {
                    let nomes: Vec<&str> =
                        config.dados.modelos.keys().map(String::as_str).collect();
                    println!("modelos: {}", nomes.join(", "));
                }
            }
            Ok(EXIT_OK)
        }

        Some(AcaoConfig::Ambiente { nome, url }) => {
            if let Some(url) = url {
                config
                    .dados
                    .ambientes
                    .insert(nome.clone(), config::Ambiente { url: url.clone() });
            } else if !config.dados.ambientes.contains_key(nome) {
                // Ativar um ambiente sem endereço deixaria a CLI apontando para o
                // padrão sem avisar, que é como se emite no lugar errado.
                return Err(Error::Local(format!(
                    "o ambiente '{nome}' não tem endereço definido; \
                     use: nfse config ambiente {nome} --url https://..."
                )));
            }
            config.dados.ambiente_ativo = nome.clone();
            config.salvar()?;
            println!("ambiente ativo: {nome} ({})", config.url_ativa());
            Ok(EXIT_OK)
        }

        Some(AcaoConfig::Modelo { acao }) => match acao {
            AcaoModelo::Salvar {
                nome,
                arquivo,
                padrao,
            } => {
                let venda = ler_json(arquivo)?;
                config.dados.modelos.insert(nome.clone(), venda);
                if *padrao || config.dados.modelo_padrao.is_none() {
                    config.dados.modelo_padrao = Some(nome.clone());
                }
                config.salvar()?;
                println!("modelo '{nome}' salvo em {}", config.caminho.display());
                if config.dados.modelo_padrao.as_deref() == Some(nome.as_str()) {
                    println!("é o modelo padrão");
                }
                Ok(EXIT_OK)
            }
            AcaoModelo::Listar => {
                if config.dados.modelos.is_empty() {
                    println!("nenhum modelo salvo");
                }
                for nome in config.dados.modelos.keys() {
                    let marca = if config.dados.modelo_padrao.as_deref() == Some(nome.as_str()) {
                        "*"
                    } else {
                        " "
                    };
                    println!("{marca} {nome}");
                }
                Ok(EXIT_OK)
            }
            AcaoModelo::Remover { nome } => {
                if config.dados.modelos.remove(nome).is_none() {
                    return Err(Error::Local(format!("modelo '{nome}' não existe")));
                }
                if config.dados.modelo_padrao.as_deref() == Some(nome.as_str()) {
                    config.dados.modelo_padrao = None;
                }
                config.salvar()?;
                println!("modelo '{nome}' removido");
                Ok(EXIT_OK)
            }
        },
    }
}

/// Resolve a venda: arquivo, modelo indicado ou modelo padrão, com as
/// substituições por cima. É o que faz a emissão do dia a dia ser só
/// `nfse emitir --valor 1500.00`.
fn montar_venda(entrada: &EntradaVenda, config: &Config) -> client::Result<Value> {
    let mut venda = match (&entrada.arquivo, &entrada.modelo) {
        (Some(caminho), _) => ler_json(caminho)?,
        (None, Some(nome)) => config.modelo(nome)?,
        (None, None) => match config.dados.modelo_padrao.as_deref() {
            Some(nome) => config.modelo(nome)?,
            None => {
                return Err(Error::Local(
                    "informe --arquivo, --modelo, ou salve um modelo padrão com \
                     'nfse config modelo salvar <nome> -a venda.json --padrao'"
                        .into(),
                ))
            }
        },
    };

    if let Some(valor) = &entrada.valor {
        config::definir(
            &mut venda,
            &["values", "vServ"],
            Value::String(valor.clone()),
        );
    }
    if let Some(competencia) = &entrada.competencia {
        config::definir(
            &mut venda,
            &["dps", "dCompet"],
            Value::String(competencia.clone()),
        );
    }
    if let Some(emissao) = &entrada.emissao {
        config::definir(
            &mut venda,
            &["dps", "dhEmi"],
            Value::String(emissao.clone()),
        );
    }
    if let Some(serie) = &entrada.serie {
        config::definir(&mut venda, &["dps", "serie"], Value::String(serie.clone()));
    }
    if let Some(descricao) = &entrada.descricao {
        config::definir(
            &mut venda,
            &["service", "description"],
            Value::String(descricao.clone()),
        );
    }
    Ok(venda)
}

// --------------------------------------------------------------- apresentação

fn codigo_do_status(resposta: &Value) -> u8 {
    match resposta.get("status").and_then(Value::as_str) {
        Some("AUTHORIZED") | Some("REGISTERED") => EXIT_OK,
        // SUBMIT_FAILED não é recusa: a transmissão caiu no meio e nem o serviço
        // sabe se a nota chegou a existir. Tratar isso como recusa levaria um
        // script a reenviar e duplicar a nota — por isso tem código próprio.
        Some("SUBMIT_FAILED") => EXIT_INDETERMINADO,
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
