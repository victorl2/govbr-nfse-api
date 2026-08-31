# nfse (CLI)

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

## Configuração

Na primeira execução a CLI cria `.nfse-cli/config.json` e avisa onde. O arquivo
guarda o **ambiente ativo** (com qual serviço falar) e os **modelos** de venda.

Procura nesta ordem: `--config`, `$NFSE_CLI_CONFIG`, `./.nfse-cli/config.json`
(configuração do projeto) e `~/.nfse-cli/config.json` (a do usuário). Uma pasta
`.nfse-cli` no diretório do projeto vence a do usuário, o que permite manter uma
configuração por cliente ou por empresa.

```bash
nfse config                                        # onde está e o que tem dentro
nfse config ambiente restrita --url https://nfse.interno:8443
nfse config ambiente restrita                      # só troca o ativo
```

O endereço vem, em ordem: `--url`, `$NFSE_URL`, ambiente ativo do config.
**Nenhum segredo entra neste arquivo**: o certificado e-CNPJ e sua senha vivem
no serviço, nunca na máquina de quem chama a CLI.

### Modelos: emitir mudando só valor e datas

Guarde uma venda inteira uma vez; depois a emissão do mês muda o que varia.

```bash
nfse config modelo salvar mensal -a venda.json --padrao
nfse config modelo listar
nfse config modelo remover mensal

nfse emitir --valor 2500.00 --competencia 2026-08-31
nfse emitir -M mensal --valor 2500.00 --descricao "Consultoria, agosto"
```

Substituições disponíveis em `emitir` e `validar`: `--valor` (`values.vServ`),
`--competencia` (`dps.dCompet`), `--emissao` (`dps.dhEmi`), `--serie`
(`dps.serie`) e `--descricao` (`service.description`). O resto do modelo segue
intacto. Sem `-a` e sem `-M`, vale o modelo padrão.

## Uso

```bash
nfse health                                   # serviço no ar? certificado válido?
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

nfse emissoes  --limite 20                   # o que este serviço emitiu
nfse distribuicao                            # o que existe no registro nacional
nfse distribuicao --salvar-em ./notas        # e grava o XML de cada documento
nfse numeracao                               # contadores atuais
nfse numeracao --serie 1 --ultimo-consumido 18
nfse validar-dps -a minha-dps.xml --municipio 3304557 --opsimpnac 3
```

`--json` em qualquer comando imprime a resposta crua do serviço, boa para `jq`.

### `emissoes` e `distribuicao` respondem perguntas diferentes

`emissoes` lê o registro local: o que **este serviço** emitiu. `distribuicao` lê
o ADN: o que **existe nacionalmente** para o CNPJ, inclusive notas emitidas pelo
portal ou por outro sistema, e notas em que outra pessoa indicou você. Também
traz os eventos (cancelamentos) vinculados.

```bash
nfse distribuicao                       # caminha do NSU 0 até o fim
nfse distribuicao --nsu 12              # só o que veio depois do NSU 12
nfse distribuicao --uma-pagina          # um lote só
nfse distribuicao --salvar-em ./notas   # grava cada XML em arquivo
```

## Códigos de saída

Emissão quase sempre roda dentro de script, então o código de saída distingue os
casos que exigem reações diferentes:

| Código | Significado |
|---|---|
| `0` | Sucesso: nota `AUTHORIZED` ou evento `REGISTERED`. |
| `1` | Erro de uso ou falha inesperada. |
| `2` | Documento recusado (`REJECTED_BY_SEFIN`, `REJECTED_LOCALLY`). |
| `3` | Serviço indisponível: fora do ar, certificado inutilizável (503) ou sobrecarregado (529). |
| `4` | Não encontrado (404). |
| `5` | `SUBMIT_FAILED`: a transmissão caiu e **não se sabe se a nota foi criada**. |

As distinções que importam na prática: `2` é problema do documento, reenviar
igual não adianta. `3` é problema do serviço, o documento pode estar certo e a
tentativa pode ser repetida. `5` é o caso delicado: a nota **pode** ter sido
criada na SEFIN, então repetir às cegas arrisca duplicar; consulte antes
(`nfse consultar CHAVE`, ou o `GET /dps/{id}` que o próprio achado sugere).

```bash
if nfse emitir -a venda.json; then
    echo "emitida"
else
    case $? in
        2) echo "recusada, corrija o documento" ;;
        3) echo "serviço fora, tente de novo mais tarde" ;;
        5) echo "indeterminado, verifique antes de reenviar" ;;
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
