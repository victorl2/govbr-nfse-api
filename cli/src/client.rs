//! HTTP client for the nfse service.
//!
//! The service does all the fiscal work — building, validating, signing and
//! transmitting the DPS. This CLI only talks to it, so everything here is a thin
//! wrapper that turns an HTTP answer into either a JSON value, some bytes, or a
//! typed error the command layer can map to an exit code.

use std::time::Duration;

use serde_json::Value;

/// What went wrong, kept separate from the message so `main` can choose an exit
/// code without matching on strings.
#[derive(Debug)]
pub enum Error {
    /// The service answered, but with an error status.
    Api {
        status: u16,
        message: String,
        url: String,
    },
    /// The service could not be reached at all.
    Unreachable { url: String, cause: String },
    /// The answer was not what this CLI knows how to read.
    Malformed(String),
    /// Something local failed — reading a file, writing the output.
    Local(String),
}

impl std::fmt::Display for Error {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Error::Api {
                status,
                message,
                url,
            } => write!(f, "o serviço respondeu {status} em {url}: {message}"),
            Error::Unreachable { url, cause } => write!(
                f,
                "não foi possível falar com o serviço em {url}: {cause}\n\
                 verifique se ele está no ar e se --url aponta para o endereço certo"
            ),
            Error::Malformed(what) => write!(f, "resposta inesperada do serviço: {what}"),
            Error::Local(what) => write!(f, "{what}"),
        }
    }
}

pub type Result<T> = std::result::Result<T, Error>;

pub struct Client {
    agent: ureq::Agent,
    base_url: String,
}

impl Client {
    pub fn new(base_url: &str, timeout: Duration) -> Self {
        let agent = ureq::AgentBuilder::new()
            .timeout_connect(Duration::from_secs(5))
            .timeout(timeout)
            .user_agent(concat!("nfse-cli/", env!("CARGO_PKG_VERSION")))
            .build();
        Client {
            agent,
            base_url: base_url.trim_end_matches('/').to_string(),
        }
    }

    fn url(&self, path: &str) -> String {
        format!("{}{}", self.base_url, path)
    }

    // ---------------------------------------------------------------- health

    /// `/health` is the one call whose non-200 answer is still a useful answer:
    /// 503 means the certificate is unusable, and the caller wants to see the
    /// body that says so. So the status comes back alongside the JSON.
    pub fn health(&self) -> Result<(u16, Value)> {
        let url = self.url("/health");
        match self.agent.get(&url).call() {
            Ok(response) => {
                let status = response.status();
                Ok((status, read_json(response, &url)?))
            }
            Err(ureq::Error::Status(status, response)) => Ok((status, read_json(response, &url)?)),
            Err(e) => Err(unreachable(&url, e)),
        }
    }

    // ------------------------------------------------------------ read paths

    pub fn certificate(&self) -> Result<Value> {
        self.get_json("/internal/certificate")
    }

    pub fn connectivity(&self) -> Result<Value> {
        self.get_json("/internal/connectivity")
    }

    pub fn note(&self, chave: &str) -> Result<Value> {
        self.get_json(&format!("/nfse/{chave}"))
    }

    pub fn note_xml(&self, chave: &str) -> Result<Vec<u8>> {
        self.get_bytes(&format!("/nfse/{chave}/xml"))
    }

    pub fn event(&self, chave: &str, tipo: &str, sequencia: u32) -> Result<Vec<u8>> {
        self.get_bytes(&format!("/nfse/{chave}/eventos/{tipo}/{sequencia}"))
    }

    pub fn emissions(&self, limit: u32) -> Result<Value> {
        self.get_json(&format!("/internal/emissions?limit={limit}"))
    }

    pub fn numbering(&self) -> Result<Value> {
        self.get_json("/internal/numbering")
    }

    /// Um lote da distribuição do ADN a partir de `nsu` (cursor exclusivo).
    pub fn distribuicao(&self, nsu: u64, com_xml: bool) -> Result<Value> {
        self.get_json(&format!("/nfse/distribuicao?nsu={nsu}&comXml={com_xml}"))
    }

    // ----------------------------------------------------------- write paths

    pub fn seed_numbering(&self, serie: &str, last_consumed: u64) -> Result<Value> {
        let url = self.url(&format!("/internal/numbering/{serie}"));
        self.send_json(
            self.agent.put(&url),
            &url,
            serde_json::json!({ "lastConsumed": last_consumed }),
        )
    }

    pub fn validate(&self, sale: Value) -> Result<Value> {
        let url = self.url("/nfse/validate");
        self.send_json(self.agent.post(&url), &url, sale)
    }

    pub fn send(&self, sale: Value) -> Result<Value> {
        let url = self.url("/nfse/send");
        self.send_json(self.agent.post(&url), &url, sale)
    }

    pub fn cancel(&self, chave: &str, body: Value, dry_run: bool) -> Result<Value> {
        // The service accepts POST on the same path deliberately: a cancellation
        // carries a mandatory body, and proxies routinely strip bodies from
        // DELETE. POST is the safer verb to send over the wire.
        let path = if dry_run {
            format!("/nfse/{chave}/cancel/validate")
        } else {
            format!("/nfse/{chave}/cancel")
        };
        let url = self.url(&path);
        self.send_json(self.agent.post(&url), &url, body)
    }

    pub fn danfse_by_chave(&self, chave: &str) -> Result<Vec<u8>> {
        self.get_bytes(&format!("/nfse/{chave}/danfse"))
    }

    pub fn danfse_from_xml(&self, xml: &[u8]) -> Result<Vec<u8>> {
        let url = self.url("/nfse/danfse");
        let response = self
            .agent
            .post(&url)
            .set("Content-Type", "application/xml")
            .send_bytes(xml)
            .map_err(|e| api_error(&url, e))?;
        read_bytes(response, &url)
    }

    pub fn dry_run(
        &self,
        dps_xml: &[u8],
        municipality: Option<&str>,
        op_simp_nac: Option<&str>,
    ) -> Result<Value> {
        let mut url = self.url("/internal/dry-run");
        let mut params: Vec<String> = Vec::new();
        if let Some(m) = municipality {
            params.push(format!("expectedMunicipality={m}"));
        }
        if let Some(o) = op_simp_nac {
            params.push(format!("expectedOpSimpNac={o}"));
        }
        if !params.is_empty() {
            url.push('?');
            url.push_str(&params.join("&"));
        }
        let response = self
            .agent
            .post(&url)
            .set("Content-Type", "application/xml")
            .send_bytes(dps_xml)
            .map_err(|e| api_error(&url, e))?;
        read_json(response, &url)
    }

    // --------------------------------------------------------------- plumbing

    fn get_json(&self, path: &str) -> Result<Value> {
        let url = self.url(path);
        let response = self
            .agent
            .get(&url)
            .call()
            .map_err(|e| api_error(&url, e))?;
        read_json(response, &url)
    }

    fn get_bytes(&self, path: &str) -> Result<Vec<u8>> {
        let url = self.url(path);
        let response = self
            .agent
            .get(&url)
            .call()
            .map_err(|e| api_error(&url, e))?;
        read_bytes(response, &url)
    }

    fn send_json(&self, request: ureq::Request, url: &str, body: Value) -> Result<Value> {
        let response = request.send_json(body).map_err(|e| api_error(url, e))?;
        read_json(response, url)
    }
}

fn read_json(response: ureq::Response, url: &str) -> Result<Value> {
    response
        .into_json::<Value>()
        .map_err(|e| Error::Malformed(format!("{url} não devolveu JSON válido: {e}")))
}

fn read_bytes(response: ureq::Response, url: &str) -> Result<Vec<u8>> {
    let mut buffer = Vec::new();
    response
        .into_reader()
        .read_to_end(&mut buffer)
        .map_err(|e| Error::Malformed(format!("falha ao ler a resposta de {url}: {e}")))?;
    Ok(buffer)
}

/// Turns a ureq failure into ours, pulling the service's own `{"message": ...}`
/// out of the body when there is one — the service explains itself well, and
/// repeating its wording beats inventing our own.
fn api_error(url: &str, error: ureq::Error) -> Error {
    match error {
        ureq::Error::Status(status, response) => {
            let body = response.into_string().unwrap_or_default();
            let message = serde_json::from_str::<Value>(&body)
                .ok()
                .and_then(|v| {
                    v.get("message")
                        .and_then(|m| m.as_str())
                        .map(|s| s.to_string())
                })
                .unwrap_or_else(|| {
                    if body.trim().is_empty() {
                        "sem detalhes no corpo da resposta".to_string()
                    } else {
                        body.trim().to_string()
                    }
                });
            Error::Api {
                status,
                message,
                url: url.to_string(),
            }
        }
        other => unreachable(url, other),
    }
}

fn unreachable(url: &str, error: ureq::Error) -> Error {
    Error::Unreachable {
        url: url.to_string(),
        cause: error.to_string(),
    }
}

use std::io::Read;
