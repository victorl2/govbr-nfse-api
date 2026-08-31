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

Na primeira execução a CLI cria `.nfse/config.json` e avisa onde. O arquivo
guarda o **ambiente ativo** (com qual serviço falar) e os **modelos** de venda.

Procura nesta ordem: `--config`, `$NFSE_CLI_CONFIG`, `./.nfse/config.json`
(configuração do projeto) e `~/.nfse/config.json` (a do usuário). Uma pasta
`.nfse` no diretório do projeto vence a do usuário, o que permite manter uma
configuração por cliente ou por empresa.

`.nfse` é uma pasta só para tudo do emissor, geralmente assim:

```
~/.nfse/
  ecnpj.p12       o certificado e-CNPJ
  config.json     ambientes e modelos da CLI
  data/           os registros de emissão do serviço (NFSE_DATA_DIR)
```

A CLI cria a pasta com permissão **0700**, porque ela divide espaço com a chave
privada.

```bash
nfse config                                     # onde está e o que tem dentro
nfse config env uat  --url http://localhost:8080
nfse config env prod --url http://localhost:8080
nfse config env uat                        # troca o ativo
nfse emitir --env prod ...                 # usa um ambiente só nesta chamada
```

> O nome do ambiente aqui escolhe apenas **uma URL**. Quem decide o `tpAmb` é o
> `NFSE_PROFILE` do serviço que está atendendo naquela URL. Por isso a CLI não
> confia no nome local: antes de emitir ela pergunta ao serviço em que ambiente
> fiscal ele está e mostra a resposta dele na confirmação.

O endereço vem, em ordem: `--url`, `$NFSE_URL`, ambiente ativo do config.
**Nenhum segredo entra neste arquivo**: o certificado e-CNPJ e sua senha vivem
no serviço, nunca na máquina de quem chama a CLI.

### Subir o serviço sozinho

Cada ambiente pode saber como levantar o próprio contêiner. Configurado uma vez,
qualquer comando que precise do serviço o sobe se ele não estiver no ar.

```bash
nfse config docker uat  --profile restrita --porta 8080 \
  --certificado ~/.nfse/ecnpj.p12 --dados ~/.nfse/data \
  --senha-comando 'security find-generic-password -a nfse -s nfse-cert -w'

nfse config docker prod --profile producao --porta 8081 \
  --certificado ~/.nfse/ecnpj.p12 --dados ~/.nfse/data \
  --senha-comando 'security find-generic-password -a nfse -s nfse-cert -w'

nfse --env uat up          # sobe agora
nfse --env uat up --pull   # baixa a imagem antes
nfse --env uat down        # para
```

UAT e produção diferem por uma única variável, o `NFSE_PROFILE`, que é quem
decide o `tpAmb`. Montar essa linha de comando à mão toda vez é o tipo de coisa
que um dia sai errada, e sair errada aqui significa emitir no ambiente trocado.

**A senha do certificado não entra no config**: guarda-se o *comando* que a
devolve, e ela só existe em memória no instante em que o contêiner sobe.

> Com uma tag móvel como `:latest`, a imagem local envelhece calada e passa a
> rodar código antigo — responde normalmente e só falha no campo que mudou. Use
> `up --pull`, ou fixe uma tag de versão com `--imagem` para produção.

### Modelos: emitir mudando só valor e datas

Guarde uma venda inteira uma vez; depois a emissão do mês muda o que varia.

Pode haver quantos modelos você quiser — um por cliente, um por tipo de serviço.

```bash
nfse config modelo salvar mensal -a venda.json --padrao
nfse config modelo salvar avulso -a outra-venda.json
nfse config modelo listar          # o marcado com * é o padrão
nfse config modelo remover avulso
```

Qual modelo é usado, em ordem: `--arquivo`, `--modelo`/`-M`, e por fim o
**modelo padrão**. A origem sai impressa antes de qualquer coisa acontecer, no
`validar` e no cabeçalho da confirmação do `emitir`:

```bash
nfse emitir -M mensal --valor 2500.00        # origem: modelo 'mensal'
nfse emitir --valor 2500.00                  # origem: modelo 'mensal' (padrão do config)
nfse emitir -a /tmp/nota.json --valor 2500.00  # origem: arquivo /tmp/nota.json
```

Com mais de um modelo salvo, saber **qual** foi usado é a diferença entre
conferir e torcer, então isso aparece junto com o ambiente fiscal na hora de
confirmar.

Substituições disponíveis em `emitir` e `validar`: `--valor` (`values.vServ`,
em reais), `--valor-moeda` (`comercioExterior.vServMoeda`, na moeda
estrangeira), `--competencia` (`dps.dCompet`), `--emissao` (`dps.dhEmi`),
`--serie` (`dps.serie`) e `--descricao` (`service.description`). O resto do
modelo segue intacto. Sem `-a` e sem `-M`, vale o modelo padrão.

Numa nota de exportação o valor aparece nos dois lugares, então troque os dois:

```bash
nfse emitir --valor 45000.00 --valor-moeda 8900.00 --competencia 2026-08-31
```

> **Série:** a SEFIN reserva as séries a partir de **50000** para o Emissor Web e
> recusa com **E0010** quem emite por software próprio nessa faixa. Para emitir
> pela API use uma série **até 49999** (confirmado ao vivo em restrita). Isso
> também evita colidir com a numeração das notas emitidas pelo portal.

## Uso

```bash
nfse health                                   # serviço no ar? certificado válido?
nfse validar   -a venda.json                 # monta e valida, sem transmitir
nfse emitir    -a venda.json                 # emite (pede confirmação)
nfse emitir    -a venda.json -y             # sem perguntar, para script
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

## Confirmação antes de emitir

`emitir` e `cancelar` mostram um resumo do documento e pedem confirmação. O
cabeçalho traz o **ambiente fiscal informado pelo próprio serviço**, não o nome
do ambiente no config local, porque é esse o mal-entendido que faz alguém emitir
uma nota com validade legal achando que estava em homologação.

```
=====================================================
  PRODUÇÃO (tpAmb=1) — a nota terá VALIDADE LEGAL
  sefin: https://sefin.nfse.gov.br/SefinNacional
-----------------------------------------------------
  emitente    ...
  tomador     ...
  série 40000   número próximo da sequência
  competência 2026-08-31
  valor       R$ 45000.00   |   8900.00 (moeda 220)
=====================================================
EMITIR esta nota em PRODUÇÃO? [s/N]
```

Só `s`, `sim`, `y` ou `yes` seguem adiante; Enter vazio não emite. Em script use
`-y`/`--sim`. **Sem terminal e sem `--sim` a CLI recusa e sai com 1** em vez de
emitir sem consentimento.

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
