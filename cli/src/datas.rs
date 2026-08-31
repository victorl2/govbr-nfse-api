//! Datas no formato de quem usa, ISO na hora de enviar.
//!
//! O layout da NFS-e exige ISO 8601 (`dCompet` como `2026-08-31`, `dhEmi` com
//! fuso). Quem digita a competência, porém, escreve `31/08/2026`. A CLI aceita
//! as duas formas na entrada, converte para ISO antes de mandar, e mostra tudo
//! de volta no formato brasileiro.
//!
//! Aceitar as duas e nunca adivinhar é o ponto: `03/04/2026` é 3 de abril, e um
//! parser que decidisse sozinho entre dia/mês e mês/dia erraria a competência de
//! uma nota fiscal sem avisar ninguém.

use crate::client::Error;

/// Fuso de Brasília. O Brasil não tem mais horário de verão desde 2019, então o
/// deslocamento é fixo; os carimbos fiscais de verdade são gerados pelo serviço,
/// que usa a tzdb do JDK.
const FUSO: &str = "-03:00";

/// `31/08/2026` ou `2026-08-31` -> `2026-08-31`.
pub fn data_para_iso(entrada: &str) -> Result<String, Error> {
    let t = entrada.trim();
    if let Some((d, m, a)) = partes_br(t) {
        return Ok(format!("{a}-{m}-{d}"));
    }
    if iso_valida(t) {
        return Ok(t.to_string());
    }
    Err(erro(t, "uma data (31/08/2026 ou 2026-08-31)"))
}

/// `31/08/2026`, `31/08/2026 14:30`, `31/08/2026 14:30:00` ou um ISO completo.
/// Sem hora, assume meia-noite; sem fuso, assume Brasília.
pub fn datahora_para_iso(entrada: &str) -> Result<String, Error> {
    let t = entrada.trim();

    if let Some((data, hora)) = t.split_once(' ') {
        if let Some((d, m, a)) = partes_br(data) {
            return Ok(format!("{a}-{m}-{d}T{}{FUSO}", hora_completa(hora)?));
        }
    }
    if let Some((d, m, a)) = partes_br(t) {
        return Ok(format!("{a}-{m}-{d}T00:00:00{FUSO}"));
    }
    // ISO: com fuso passa direto; sem fuso, recebe o de Brasília.
    if let Some((data, resto)) = t.split_once('T') {
        if iso_valida(data) {
            let tem_fuso = resto.contains('+') || resto.contains('-') || resto.ends_with('Z');
            return Ok(if tem_fuso {
                t.to_string()
            } else {
                format!("{data}T{}{FUSO}", hora_completa(resto)?)
            });
        }
    }
    if iso_valida(t) {
        return Ok(format!("{t}T00:00:00{FUSO}"));
    }
    Err(erro(
        t,
        "uma data/hora (31/08/2026, 31/08/2026 14:30 ou ISO)",
    ))
}

/// Para exibição: `2026-08-31` -> `31/08/2026`, e um ISO com hora vira
/// `31/08/2026 14:30`. O que não for reconhecido volta como veio, porque um
/// resumo é para ler, não para falhar.
pub fn para_br(iso: &str) -> String {
    let t = iso.trim();
    let (data, hora) = match t.split_once('T') {
        Some((d, h)) => (d, Some(h)),
        None => (t, None),
    };
    let Some((a, m, d)) = tres_partes(data, '-') else {
        return t.to_string();
    };
    if a.len() != 4 {
        return t.to_string();
    }
    match hora {
        Some(h) => {
            let hm: String = h.chars().take(5).collect();
            format!("{d}/{m}/{a} {hm}")
        }
        None => format!("{d}/{m}/{a}"),
    }
}

fn partes_br(t: &str) -> Option<(String, String, String)> {
    let (d, m, a) = tres_partes(t, '/')?;
    if d.len() > 2 || m.len() > 2 || a.len() != 4 {
        return None;
    }
    let (dn, mn) = (d.parse::<u32>().ok()?, m.parse::<u32>().ok()?);
    a.parse::<u32>().ok()?;
    if !(1..=31).contains(&dn) || !(1..=12).contains(&mn) {
        return None;
    }
    Some((format!("{dn:02}"), format!("{mn:02}"), a.to_string()))
}

fn tres_partes(t: &str, sep: char) -> Option<(String, String, String)> {
    let p: Vec<&str> = t.split(sep).collect();
    if p.len() != 3 || p.iter().any(|x| x.is_empty()) {
        return None;
    }
    Some((p[0].to_string(), p[1].to_string(), p[2].to_string()))
}

fn iso_valida(t: &str) -> bool {
    match tres_partes(t, '-') {
        Some((a, m, d)) => {
            a.len() == 4
                && a.parse::<u32>().is_ok()
                && (1..=12).contains(&m.parse::<u32>().unwrap_or(0))
                && (1..=31).contains(&d.parse::<u32>().unwrap_or(0))
        }
        None => false,
    }
}

fn hora_completa(h: &str) -> Result<String, Error> {
    let p: Vec<&str> = h.trim().split(':').collect();
    let n = |x: &str| x.parse::<u32>().ok();
    match p.len() {
        2 => match (n(p[0]), n(p[1])) {
            (Some(hh), Some(mm)) if hh < 24 && mm < 60 => Ok(format!("{hh:02}:{mm:02}:00")),
            _ => Err(erro(h, "uma hora (14:30)")),
        },
        3 => match (n(p[0]), n(p[1]), n(p[2].split('.').next().unwrap_or(""))) {
            (Some(hh), Some(mm), Some(ss)) if hh < 24 && mm < 60 && ss < 60 => {
                Ok(format!("{hh:02}:{mm:02}:{ss:02}"))
            }
            _ => Err(erro(h, "uma hora (14:30:00)")),
        },
        _ => Err(erro(h, "uma hora (14:30 ou 14:30:00)")),
    }
}

fn erro(valor: &str, esperado: &str) -> Error {
    Error::Local(format!("'{valor}' não é {esperado}"))
}
