# nfse

Serviço para emissão de **NFS-e** (Nota Fiscal de Serviço eletrônica) pelo
**Sistema Nacional NFS-e** (padrão nacional). **Java 25**.

O serviço monta, valida, assina e transmite a DPS, registra eventos e gera a
DANFSe localmente. Quem chama a API descreve a venda em JSON, nunca lida com XML.

**Status: em produção.** A primeira NFS-e com validade legal foi emitida por este
serviço em **31/08/2026**: DPS montada, validada, assinada e transmitida à SEFIN
em `tpAmb=1`, autorizada, com a DANFSe gerada e a nota conferida no portal do
emissor. Antes disso o mesmo fluxo foi exercitado à exaustão em produção restrita,
incluindo cancelamento e substituição.

Carga do certificado, mTLS, assinatura XMLDSig, validação XSD + lint de regras de
negócio, emissão síncrona, eventos, DANFSe, numeração durável e leitura do
registro nacional (ADN) estão implementados e usados de verdade. O caminho até
aqui está em [docs/07](docs/07-sandbox-to-go-live.md).

## Documentação
→ **[docs/README.md](docs/README.md)**, documentação completa de integração
(arquitetura, pré-requisitos, referência da API, layout DPS/NFS-e, eventos,
assinatura, sandbox até go-live e o desenho em Java).

## CLI

Há uma CLI em Rust, distribuída como binário único e sem runtime, que fala com
o serviço:

```bash
nfse health
nfse emitir -a venda.json
nfse danfse CHAVE -s danfse.pdf
```

Binários para Linux (estáticos, musl), macOS e Windows são publicados em cada
release. Veja [`cli/`](cli) para instalação, todos os comandos e os códigos de
saída, que distinguem "documento recusado" de "serviço fora do ar".

## Como executar
Requer **JDK 25** e Maven.

```bash
# 1. Gera um certificado dummy autoassinado para desenvolvimento (sem e-CNPJ real)
./scripts/generate-dummy-cert.sh

# 2. Compila e testa (o pipeline de assinatura é verificado com o cert dummy)
mvn package          # -> target/nfse.jar + target/lib/

# 3a. Execução local (cert dummy, sem rede gov.br)
NFSE_PROFILE=local NFSE_CERT_PATH=certs/dummy.p12 java -jar target/nfse.jar

# 3b. Execução contra produção restrita (e-CNPJ A1 REAL, comprova o mTLS)
NFSE_PROFILE=restrita NFSE_CERT_PATH=/secure/ecnpj.p12 NFSE_CERT_PASSWORD=*** \
  java -jar target/nfse.jar
# depois: curl localhost:8080/internal/certificate  e  /internal/connectivity
```

## Emissão

```bash
curl -s -X POST localhost:8080/nfse/validate -H 'Content-Type: application/json' -d @venda.json
# → {"valid": true|false, "findings": [...], "dpsXml": "<DPS...>"}

curl -s -X POST localhost:8080/nfse/send -H 'Content-Type: application/json' -d @venda.json
# → {"status": "AUTHORIZED"|"REJECTED_BY_SEFIN"|"REJECTED_LOCALLY"|"SUBMIT_FAILED",
#    "findings": [...], "dpsXml": "<DPS assinada>",
#    "chaveAcesso": "...", "nfseXml": "<NFSe...>"}
```

O formato da requisição está em `EmitNfseRequest`: `emitter`, `dps`, `service`,
`values` e, opcionalmente, `ibsCbs`, `tomador`, `intermediario`,
`comercioExterior` e `substituicao`, exportação de serviço é um fluxo de primeira
classe. `values` também aceita `descontos` (incondicionado/condicionado),
`deducaoReducao` e `tributacaoFederal` (PIS/COFINS mais as retenções de
IRRF/CSLL/CP); `service.infoCompl` carrega `xInfComp`, `docRef` e `xPed`.
`substituicao` (`chSubstda` + `cMotivo` + `xMotivo`) substitui uma nota existente:
a SEFIN cancela a anterior e emite esta no lugar, na mesma chamada.

> **Escolha a série com cuidado.** A SEFIN reserva as séries a partir de
> **50000** para o Emissor Web e recusa com **E0010** quem emite por software
> próprio nessa faixa. Emissão por API precisa de uma série **até 49999**, e usar
> uma série nova evita colidir com a numeração das notas emitidas pelo portal.
> (Confirmado ao vivo: 1, 900, 9999, 10000, 40000 e 49999 são aceitas; 50000,
> 60000 e 70000 não.)

Uma transmissão que falha **em trânsito** é ambígua, então o serviço consulta o id
determinístico da DPS em `GET /dps/{id}` antes de reportar falha: se a SEFIN já
havia criado a nota, o resultado volta como `AUTHORIZED` em vez de ser duplicado
por uma retentativa.

### Numeração e registro local

`dps.number` é **opcional**. Omitido, o serviço aloca o próximo número da série a
partir de um contador durável, de modo que dois chamadores ou um restart não
colidam. Informá-lo explicitamente continua válido e é como se reenvia uma
transmissão que falhou usando o número já consumido: o mesmo (CNPJ, série, número)
gera o mesmo id de DPS, reservado antes de qualquer envio, então uma repetição
devolve o resultado armazenado em vez de emitir duas vezes.

Toda transmissão é registrada antes de sair, e a nota autorizada e sua DPS
assinada são guardadas, a legislação exige cinco anos de armazenamento, e sem cópia local
uma resposta perdida obriga a varrer o ADN atrás da própria nota.

```bash
curl -s localhost:8080/internal/numbering                      # → {"1": 18}
curl -s -X PUT localhost:8080/internal/numbering/1 \
     -H 'Content-Type: application/json' -d '{"lastConsumed":18}'
# reduzir um contador é recusado com 400, reemitiria um número já usado

curl -s localhost:8080/nfse/{chaveAcesso}              # o registro armazenado
curl -s localhost:8080/nfse/{chaveAcesso}/xml          # a NFS-e autorizada
curl -s 'localhost:8080/internal/emissions?limit=20'   # tentativas recentes
```

### Registro nacional (ADN)

`/internal/emissions` conta o que **este serviço** emitiu. O ADN (Ambiente de
Dados Nacional) conta o que **existe nacionalmente** para o CNPJ, inclusive notas
emitidas pelo portal ou por outro sistema, e notas em que outra pessoa indicou
você como tomador ou intermediário. É por aí que se reencontra uma nota cuja
resposta se perdeu.

```bash
curl -s 'localhost:8080/nfse/distribuicao?nsu=0'            # lote a partir do NSU
curl -s 'localhost:8080/nfse/distribuicao?nsu=0&comXml=true' # com o XML de cada documento
```

O `nsu` é um cursor **exclusivo**: pedir 0 devolve a partir do NSU 1. Caminhe
passando o `ultimoNsu` recebido até o lote vir vazio. Confirmado ao vivo em
produção restrita, incluindo o detalhe de que o fim da lista chega como **404
com o código E2220**, e não como uma lista vazia.

> **Monte `/var/lib/nfse`.** Guarda os contadores e todas as notas emitidas, e
> nenhum dos dois é reconstituível. Uma nota rejeitada localmente devolve seu
> número; uma rejeitada **pela SEFIN** o mantém, porque aquele número foi gasto.

O estado fica em um subdiretório **por ambiente** (`restrita/`, `producao/`,
`local/`), e isso não é arrumação. O `infDPS@Id` não carrega `tpAmb`, então
restrita e produção produzem o **mesmo** id para a mesma (série, número). Como o
registro local é o guarda de idempotência, um diretório único faria uma emissão
em produção esbarrar no registro de restrita e voltar `AUTHORIZED` com a chave de
restrita, **sem emitir nada**: um sucesso falso, exibindo como nota real uma
chave sem valor legal. Isso também mantém a numeração de produção começando
limpa, como a legislação espera.

## Cancelamento e eventos
Uma nota emitida é anulada por um *Pedido de Registro de Evento* assinado
([docs/05](docs/05-events.md)). O cancelamento tem prazo municipal; vencido, a
nota só pode ser substituída por uma DPS com `substituicao`.

```bash
curl -s -X DELETE localhost:8080/nfse/{chaveAcesso}/cancel \
  -H 'Content-Type: application/json' \
  -d '{"cnpjAutor":"...","cMotivo":"1","xMotivo":"Erro na emissao: ..."}'
# → {"status":"REGISTERED"|"REJECTED_BY_SEFIN"|"REJECTED_LOCALLY"|"SUBMIT_FAILED", ...}

curl -s -X POST localhost:8080/nfse/{chaveAcesso}/cancel/validate ...  # simulação
curl -s        localhost:8080/nfse/{chaveAcesso}/eventos/101101/1      # lê um evento
```

O mesmo caminho aceita **POST**: o cancelamento exige corpo na requisição, e
corpos em DELETE, embora legais, são frequentemente removidos por proxies.
Cancelar não apaga nada, a nota continua recuperável, com o evento anexado.

`cMotivo`: 1 = Erro na Emissão, 2 = Serviço não Prestado, 9 = Outros. `xMotivo`
exige no mínimo 15 caracteres, o XSD oficial determina isso, e o serviço detecta
offline.

Todo carimbo de tempo fiscal (`dhEmi`, `dCompet`, `dhEvento`) usa o **horário de
Brasília**, independente do fuso do host, para que um contêiner em UTC não lance
uma nota na competência errada.

## DANFSe

Gerada localmente conforme a NT-008 (a API oficial de geração foi desativada em
2026-08-03; o software emissor deve produzir a sua). O layout replica fielmente o
template DANFSe v2.0 do portal, mesma geometria, fontes embutidas e QR Code.

```bash
curl -s localhost:8080/nfse/{chaveAcesso}/danfse -o danfse.pdf   # busca a nota na SEFIN
curl -s -X POST localhost:8080/nfse/danfse -H 'Content-Type: application/xml' \
  --data-binary @nfse.xml -o danfse.pdf                          # offline, do XML que você já tem
```

Para validar uma DPS já montada, sem transmitir nada:

```bash
curl -s -X POST 'localhost:8080/internal/dry-run?expectedMunicipality=3304557&expectedOpSimpNac=3' \
  -H 'Content-Type: application/xml' --data-binary @minha-dps.xml
```

Os parâmetros de consulta são as expectativas de quem chama (código IBGE do
município; `opSimpNac` 1=Não Optante, 2=MEI, 3=ME/EPP), omita qualquer um para
pular aquela verificação. O serviço em si é neutro quanto à empresa.

## Docker
Imagens prontas são publicadas no GitHub Container Registry a cada push na `main`,
para **linux/amd64 e linux/arm64**, e só depois que os testes e as verificações da
imagem passam:

```bash
docker pull ghcr.io/victorl2/govbr-nfse-api:latest
```

Tags disponíveis: `latest`, `sha-<commit>` e, em tags de versão, `x.y.z` e `x.y`.
Para compilar localmente:

```bash
docker build -t nfse .
docker run -m 192m -p 8080:8080 \
  -v /secure/ecnpj.p12:/secure/ecnpj.p12:ro \
  -v nfse-data:/var/lib/nfse \
  -e NFSE_PROFILE=restrita \
  -e NFSE_CERT_PATH=/secure/ecnpj.p12 -e NFSE_CERT_PASSWORD=... \
  nfse
```

A imagem é autocontida, schemas, truststore, fontes da DANFSe e tabela do IBGE
viajam dentro do jar. Só o certificado é montado em tempo de execução, e **pode
continuar pertencendo ao root com `chmod 600`**: o entrypoint sobe como root
apenas o suficiente para copiá-lo para um caminho privado do contêiner (modo 400)
e então executa a JVM como o usuário sem privilégios `nfse`, sem tocar no arquivo
do host. Passar `--user` pula essa etapa, e aí o uid informado precisa conseguir
ler o certificado sozinho.

> **Use `-m 192m`.** É o menor limite que nunca pressiona o coletor, de 1 a 16
> renderizações simultâneas. Memória aqui compra vazão, não latência: com 8
> renderizações simultâneas, 192m sustenta **35 req/s** contra 8,5 em 128m. Acima
> disso o ganho é marginal, use 256m só se mais de 8 renderizações forem ficar em
> voo ao mesmo tempo, e não desça abaixo de 128m.

## Operação

`GET /health` reporta a única coisa que interrompe silenciosamente toda emissão:
o certificado. Responde **503 `DOWN`** quando o e-CNPJ não carrega ou está
vencido, e **200 `UP`** caso contrário, com `daysToExpiry` e um aviso abaixo de 30
dias. Permanece UP enquanto o certificado está apenas se aproximando do
vencimento, derrubar um serviço saudável um mês antes não ajuda ninguém; alerte
pelo aviso.

```json
{"status":"UP","certificate":{"notAfter":"2027-01-20T12:10:11Z","daysToExpiry":144},"warnings":[]}
```

A geração da DANFSe é limitada: é intensiva em CPU e alocação, então concorrência
ilimitada transforma um pico de carga em pressão de memória. Renderizações além de
`NFSE_MAX_CONCURRENT_RENDERS` aguardam na fila por
`NFSE_RENDER_QUEUE_TIMEOUT_SECONDS` e então recebem **529 Service Overloaded**,
uma condição retentável, deliberadamente distinta do 503 que significa que o
serviço não consegue emitir nota alguma.

| Variável | Padrão | Função |
|---|---|---|
| `NFSE_PROFILE` | `local` | `local` / `restrita` / `producao`, define host **e** `tpAmb` juntos. |
| `NFSE_CERT_PATH` / `NFSE_CERT_PASSWORD` |, | O e-CNPJ A1. Nunca embutido na imagem. |
| `NFSE_DATA_DIR` | `/var/lib/nfse` | Contadores e registros de emissão. **Monte.** |
| `NFSE_MAX_CONCURRENT_RENDERS` | um por núcleo | Limite de DANFSe simultâneas. |
| `NFSE_CONNECTIVITY_CHECK` | ligado fora de `local` | Sonda mTLS na inicialização. |

## Desempenho

A imagem carrega um **cache AOT do Project Leyden** (Java 25, JEP 483/515),
treinado durante o build. Medido a partir da mesma origem, com execuções
intercaladas:

| | sem o cache | com o cache |
|---|---|---|
| `docker run` → `/health` respondendo | 1.000 ms | **571 ms** |
| início do contêiner → `/health` respondendo | 680 ms | **265 ms** |
| primeira DANFSe (a frio) | 307 ms | **199 ms** |
| DANFSe aquecida (p50) | **51 ms** | 56 ms |
| imagem | **78 MB** | 131 MB |
| pico de RSS, 20 renderizações em `-m 192m` | 188 MiB | **180 MiB** |

O custo é a renderização aquecida: reproduzir os perfis gravados custa cerca de
5 ms (~10%) em regime permanente. Em compensação o cache **reduz** a memória,
porque as regiões mapeadas do arquivo substituem metadados alocados no heap.

O treinamento (estágio 2c do Dockerfile) sobe o serviço real, executa 60
renderizações de DANFSe e 15 ciclos de validate + send + cancel, e o encerra com
SIGTERM para que a JVM escreva o cache. **Nada real é tocado: o certificado é um
par de chaves descartável gerado no build e a SEFIN é um stub WireMock
(`training/`), o build não precisa de e-CNPJ e nunca chama o gov.br.** O
treinamento exige que sua própria primeira emissão volte `AUTHORIZED`, porque um
stub que falhe em silêncio ainda produziria um cache plausível e vazio dos
caminhos de emissão.

`AOTMode=auto` é deliberado: um cache que não corresponda ao runtime é ignorado
com registro em log, em vez de fatal. Outras opções foram medidas e descartadas,
CDS puro, `-XX:TieredStopAtLevel=1`, pré-aquecimento em segundo plano, outros
fornecedores de JDK (Corretto e Zulu: idênticos dentro do ruído) e o CRaC, o mais
rápido de todos, recusado porque um checkpoint é um despejo de memória sem
criptografia e exige contêiner privilegiado.

<sub>Máquina de referência: MacBook Pro (M1 Pro, 10 núcleos, 32 GB, macOS 26.4)
com Docker Desktop 24.0.7, 10 CPUs e 7,7 GiB para a VM Linux; JVM Temurin 25.0.4
em aarch64/musl. A DANFSe é limitada por CPU, então a vazão acompanha o número de
núcleos, espere números menores em uma instância de 2 vCPUs. Os limites de
memória não dependem de CPU.</sub>

## Estrutura do repositório

| Caminho | Conteúdo |
|------|----------|
| [`docs/`](docs) | Documentação de integração (comece por aqui). |
| [`src/`](src) | O serviço. Sem framework, veja `NfseApplication.main`. |
| [`schemas/`](schemas) | XSDs oficiais da NFS-e. **v1.01 = 2026-02-09.** |
| [`cli/`](cli) | CLI em Rust, distribuída como binário único. |
| [`training/`](training) | Stub WireMock e carga usados no treino do cache AOT. |
| [`scripts/`](scripts) | Utilitários de desenvolvimento. |
| [`reference/`](reference) | Texto extraído dos manuais oficiais do contribuinte. |

## Fontes oficiais

- Portal: <https://www.gov.br/nfse/pt-br/biblioteca/documentacao-tecnica>
- APIs (restrita/produção): <https://www.gov.br/nfse/pt-br/biblioteca/documentacao-tecnica/apis-prod-restrita-e-producao>
- Schemas: `nfse-esquemas_xsd-v1-01-20260209.zip` (layout de produção vigente).

## ⚠️ Nunca versione segredos

O **certificado e-CNPJ** ICP-Brasil (`.pfx`/`.p12`) e sua senha nunca podem entrar
no repositório. Veja o [`.gitignore`](.gitignore).
