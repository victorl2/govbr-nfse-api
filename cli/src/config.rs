//! Configuração da CLI: ambiente ativo e modelos de venda.
//!
//! Duas coisas moram aqui. O **ambiente** diz com qual serviço falar, para não
//! repetir `--url` a cada chamada e, principalmente, para não emitir contra
//! produção achando que era restrita. Os **modelos** guardam uma venda inteira
//! já preenchida, de modo que a emissão do dia a dia mude só o valor e as datas.
//!
//! Nada de segredo entra neste arquivo. O certificado e-CNPJ e sua senha vivem
//! no serviço, nunca na máquina de quem chama a CLI.
//!
//! A pasta é `.nfse`, a mesma onde costuma ficar o certificado. Uma pasta só
//! para tudo do emissor, e por isso ela é criada com permissão 0700: se um dia
//! o e-CNPJ estiver ao lado, o diretório já nasce fechado.

use std::collections::BTreeMap;
use std::path::{Path, PathBuf};

use serde::{Deserialize, Serialize};
use serde_json::Value;

use crate::client::Error;

pub const NOME_PASTA: &str = ".nfse";
pub const NOME_ARQUIVO: &str = "config.json";
pub const URL_PADRAO: &str = "http://localhost:8080";

#[derive(Serialize, Deserialize, Clone, Debug)]
pub struct Ambiente {
    pub url: String,
    /// Como subir o serviço deste ambiente, quando ele não estiver no ar.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub docker: Option<Docker>,
}

/// O suficiente para levantar o contêiner do serviço.
///
/// A senha do certificado NÃO fica aqui: o que se guarda é o **comando** que a
/// devolve (o Keychain, um gerenciador de segredos, o que for). Assim o config
/// continua sem segredo nenhum, e a senha só existe em memória no instante em
/// que o contêiner sobe.
#[derive(Serialize, Deserialize, Clone, Debug)]
#[serde(rename_all = "camelCase")]
pub struct Docker {
    pub imagem: String,
    pub container: String,
    pub porta: u16,
    /// NFSE_PROFILE: `restrita` ou `producao`. É o que decide o tpAmb.
    pub profile: String,
    pub certificado: String,
    pub dados: String,
    /// Comando que imprime a senha do certificado na saída padrão.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub senha_comando: Option<String>,
    #[serde(default = "memoria_padrao")]
    pub memoria: String,
}

fn memoria_padrao() -> String {
    // Medido: 192m é o menor limite que nunca pressiona o coletor.
    "192m".to_string()
}

#[derive(Serialize, Deserialize, Debug)]
#[serde(rename_all = "camelCase")]
pub struct Dados {
    /// Qual ambiente está valendo agora.
    pub ambiente_ativo: String,
    /// Nome do ambiente para o endereço do serviço.
    pub ambientes: BTreeMap<String, Ambiente>,
    /// Vendas já preenchidas, prontas para reemissão.
    #[serde(default)]
    pub modelos: BTreeMap<String, Value>,
    /// Modelo usado quando `emitir` não recebe nem arquivo nem `--modelo`.
    #[serde(default)]
    pub modelo_padrao: Option<String>,
}

impl Default for Dados {
    fn default() -> Self {
        let mut ambientes = BTreeMap::new();
        // Só `local` vem pronto: os endereços de restrita e produção dependem de
        // onde cada um hospeda o serviço, e inventar um valor plausível aqui
        // seria a forma mais fácil de emitir no ambiente errado.
        ambientes.insert(
            "local".to_string(),
            Ambiente {
                url: URL_PADRAO.to_string(),
                docker: None,
            },
        );
        Dados {
            ambiente_ativo: "local".to_string(),
            ambientes,
            modelos: BTreeMap::new(),
            modelo_padrao: None,
        }
    }
}

#[derive(Debug)]
pub struct Config {
    pub caminho: PathBuf,
    pub dados: Dados,
    /// Verdadeiro quando o arquivo acabou de ser criado por esta execução.
    pub recem_criado: bool,
}

impl Config {
    /// Procura, nesta ordem: o caminho explícito, `$NFSE_CLI_CONFIG`, uma pasta
    /// `.nfse` no diretório atual (configuração do projeto) e, por fim,
    /// `~/.nfse` (a do usuário). Se nada existir, cria a do usuário.
    pub fn carregar(explicito: Option<&Path>) -> Result<Config, Error> {
        let caminho = Self::resolver_caminho(explicito);

        if !caminho.exists() {
            let dados = Dados::default();
            Self::gravar(&caminho, &dados)?;
            return Ok(Config {
                caminho,
                dados,
                recem_criado: true,
            });
        }

        let bruto = std::fs::read(&caminho).map_err(|e| {
            Error::Local(format!("não foi possível ler {}: {e}", caminho.display()))
        })?;
        let dados: Dados = serde_json::from_slice(&bruto).map_err(|e| {
            Error::Local(format!(
                "{} não é um config válido: {e}\n\
                 apague o arquivo para recriá-lo com os valores padrão",
                caminho.display()
            ))
        })?;
        Ok(Config {
            caminho,
            dados,
            recem_criado: false,
        })
    }

    fn resolver_caminho(explicito: Option<&Path>) -> PathBuf {
        if let Some(p) = explicito {
            return p.to_path_buf();
        }
        if let Ok(p) = std::env::var("NFSE_CLI_CONFIG") {
            if !p.trim().is_empty() {
                return PathBuf::from(p);
            }
        }
        let no_projeto = PathBuf::from(NOME_PASTA).join(NOME_ARQUIVO);
        if no_projeto.exists() {
            return no_projeto;
        }
        match diretorio_do_usuario() {
            Some(home) => home.join(NOME_PASTA).join(NOME_ARQUIVO),
            None => no_projeto,
        }
    }

    pub fn salvar(&self) -> Result<(), Error> {
        Self::gravar(&self.caminho, &self.dados)
    }

    fn gravar(caminho: &Path, dados: &Dados) -> Result<(), Error> {
        if let Some(pasta) = caminho.parent() {
            if !pasta.as_os_str().is_empty() {
                std::fs::create_dir_all(pasta).map_err(|e| {
                    Error::Local(format!("não foi possível criar {}: {e}", pasta.display()))
                })?;
                fechar_permissoes(pasta);
            }
        }
        let texto = serde_json::to_string_pretty(dados)
            .map_err(|e| Error::Local(format!("falha ao serializar o config: {e}")))?;
        std::fs::write(caminho, format!("{texto}\n")).map_err(|e| {
            Error::Local(format!(
                "não foi possível gravar {}: {e}",
                caminho.display()
            ))
        })?;
        // 0600: os modelos guardam a venda inteira, com CNPJ, contato e dados do
        // tomador. É dado de cliente, não configuração pública.
        fechar_arquivo(caminho);
        Ok(())
    }

    /// O endereço do ambiente ativo, ou o padrão quando o ambiente ativo não
    /// tem entrada — é melhor seguir com o padrão do que travar a CLI por causa
    /// de um config editado à mão.
    pub fn url_ativa(&self) -> &str {
        self.dados
            .ambientes
            .get(&self.dados.ambiente_ativo)
            .map(|a| a.url.as_str())
            .unwrap_or(URL_PADRAO)
    }

    pub fn modelo(&self, nome: &str) -> Result<Value, Error> {
        self.dados.modelos.get(nome).cloned().ok_or_else(|| {
            let disponiveis: Vec<&str> = self.dados.modelos.keys().map(String::as_str).collect();
            let lista = if disponiveis.is_empty() {
                "nenhum modelo salvo ainda; use 'nfse config modelo salvar'".to_string()
            } else {
                format!("modelos disponíveis: {}", disponiveis.join(", "))
            };
            Error::Local(format!("modelo '{nome}' não existe. {lista}"))
        })
    }
}

/// 0700 na pasta: ela divide espaço com o e-CNPJ, e um diretório legível por
/// todos ao lado de uma chave privada é um convite. Silencioso de propósito —
/// não conseguir endurecer a permissão não é motivo para a CLI parar.
#[cfg(unix)]
fn fechar_permissoes(pasta: &Path) {
    use std::os::unix::fs::PermissionsExt;
    let _ = std::fs::set_permissions(pasta, std::fs::Permissions::from_mode(0o700));
}

#[cfg(not(unix))]
fn fechar_permissoes(_pasta: &Path) {}

#[cfg(unix)]
fn fechar_arquivo(arquivo: &Path) {
    use std::os::unix::fs::PermissionsExt;
    let _ = std::fs::set_permissions(arquivo, std::fs::Permissions::from_mode(0o600));
}

#[cfg(not(unix))]
fn fechar_arquivo(_arquivo: &Path) {}

fn diretorio_do_usuario() -> Option<PathBuf> {
    for chave in ["HOME", "USERPROFILE"] {
        if let Ok(valor) = std::env::var(chave) {
            if !valor.trim().is_empty() {
                return Some(PathBuf::from(valor));
            }
        }
    }
    None
}

/// Aplica um valor em `venda`, criando os objetos intermediários que faltarem.
/// É o que permite a um modelo guardar a venda inteira e a emissão do dia mudar
/// só `values.vServ` e as datas.
pub fn definir(venda: &mut Value, caminho: &[&str], valor: Value) {
    let Some((ultimo, intermediarios)) = caminho.split_last() else {
        return;
    };
    let mut atual = venda;
    for chave in intermediarios {
        if !atual.is_object() {
            *atual = Value::Object(serde_json::Map::new());
        }
        atual = atual
            .as_object_mut()
            .expect("acabou de virar objeto")
            .entry((*chave).to_string())
            .or_insert_with(|| Value::Object(serde_json::Map::new()));
    }
    if !atual.is_object() {
        *atual = Value::Object(serde_json::Map::new());
    }
    atual
        .as_object_mut()
        .expect("acabou de virar objeto")
        .insert((*ultimo).to_string(), valor);
}
