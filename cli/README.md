# nfse — CLI

Linha de comando para o serviço de emissão de NFS-e. O serviço faz todo o
trabalho fiscal (monta, valida, assina e transmite a DPS); esta CLI é uma casca
sobre a API HTTP dele.

Binário único, sem runtime: baixe, dê `chmod +x` e use. As versões para Linux são
estáticas (musl) e rodam em qualquer distribuição, inclusive em contêineres
`scratch`.

## Instalação

Baixe o binário da sua plataforma em
[Releases](https://github.com/victorl2/govbr-nfse-api/releases), ou compile:

```bash
cargo build --release      # -> target/release/nfse
```

## Uso

O endereço do serviço vem de `--url` ou da variável `NFSE_URL`
(padrão `http://localhost:8080`).

```bash
export NFSE_URL=http://localhost:8080

nfse saude                                   # serviço no ar? certificado válido?
nfse validar   -a venda.json                 # monta e valida, sem transmitir
nfse emitir    -a venda.json                 # emite de verdade
nfse emitir    -a venda.json --salvar-xml nota.xml

nfse consultar CHAVE                         # registro local da nota
nfse xml       CHAVE -s nota.xml             # XML autorizado, como arquivado
nfse danfse    CHAVE -s danfse.pdf           # PDF, buscando a nota na SEFIN
nfse danfse    -a nota.xml -s danfse.pdf     # PDF a partir do XML que você já tem

nfse cancelar  CHAVE -m 1 -j "Erro na emissao do documento" --simular
nfse cancelar  CHAVE -m 1 -j "Erro na emissao do documento"
nfse evento    CHAVE -t 101101 -s 1

nfse emissoes  --limite 20                   # tentativas recentes
nfse numeracao                               # contadores atuais
nfse numeracao --serie 1 --ultimo-consumido 18
nfse validar-dps -a minha-dps.xml --municipio 3304557 --opsimpnac 3
```

`--json` em qualquer comando imprime a resposta crua do serviço, boa para `jq`.

## Códigos de saída

Emissão quase sempre roda dentro de script, então o código de saída distingue os
casos que exigem reações diferentes:

| Código | Significado |
|---|---|
| `0` | Sucesso — nota `AUTHORIZED` ou evento `REGISTERED`. |
| `1` | Erro de uso ou falha inesperada. |
| `2` | Documento recusado (`REJECTED_BY_SEFIN`, `REJECTED_LOCALLY`, `SUBMIT_FAILED`). |
| `3` | Serviço indisponível: fora do ar, certificado inutilizável (503) ou sobrecarregado (529). |
| `4` | Não encontrado (404). |

A diferença entre `2` e `3` é a que importa na prática: `2` significa que o
documento tem um problema e reenviar igual não vai adiantar; `3` significa que o
documento pode estar correto e a tentativa pode ser repetida.

```bash
if nfse emitir -a venda.json; then
    echo "emitida"
else
    case $? in
        2) echo "recusada — corrija o documento" ;;
        3) echo "serviço fora — tente de novo mais tarde" ;;
        *) echo "erro inesperado" ;;
    esac
fi
```

## Desenvolvimento

```bash
cargo test          # inclui um servidor HTTP de mentira com as respostas reais do serviço
cargo clippy --all-targets -- -D warnings
cargo fmt --check
```
