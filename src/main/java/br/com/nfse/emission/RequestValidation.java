package br.com.nfse.emission;

import br.com.nfse.event.CancelEventRequest;

import java.util.ArrayList;
import java.util.List;

/**
 * Structural checks on an incoming request, replacing the bean-validation
 * annotations that came with hibernate-validator (528 classes for what amounts
 * to a handful of null checks).
 *
 * <p>Scope is deliberately narrow: this only catches a request that could not
 * possibly build a document — a missing group, or a blank field the layout
 * requires. Everything substantive (schema conformance, business rules) is
 * already covered, and covered better, by the XSD stage and the linter.
 */
public final class RequestValidation {

    private RequestValidation() {
    }

    public static List<String> problems(EmitNfseRequest r) {
        List<String> problems = new ArrayList<>();
        if (r == null) {
            return List.of("request body is empty");
        }
        required(problems, "emitter", r.emitter());
        required(problems, "dps", r.dps());
        required(problems, "service", r.service());
        required(problems, "values", r.values());

        if (r.emitter() != null) {
            notBlank(problems, "emitter.municipality", r.emitter().municipality());
            notBlank(problems, "emitter.opSimpNac", r.emitter().opSimpNac());
            if (isBlank(r.emitter().cnpj()) && isBlank(r.emitter().cpf())) {
                problems.add("emitter needs either cnpj or cpf");
            }
        }
        if (r.dps() != null) {
            notBlank(problems, "dps.serie", r.dps().serie());
            // dps.number is deliberately optional: omitted, the service allocates
            // the next número for the série from its own durable counter. Supplying
            // one is still allowed, and is how a failed submission is retried on
            // exactly the número it already used.
        }
        if (r.service() != null) {
            notBlank(problems, "service.cTribNac", r.service().cTribNac());
            notBlank(problems, "service.description", r.service().description());
        }
        if (r.values() != null) {
            notBlank(problems, "values.vServ", r.values().vServ());
            notBlank(problems, "values.tribISSQN", r.values().tribISSQN());
            notBlank(problems, "values.tpRetISSQN", r.values().tpRetISSQN());
        }
        if (r.substituicao() != null) {
            notBlank(problems, "substituicao.chSubstda", r.substituicao().chSubstda());
            notBlank(problems, "substituicao.cMotivo", r.substituicao().cMotivo());
        }
        person(problems, "tomador", r.tomador());
        person(problems, "intermediario", r.intermediario());
        if (r.ibsCbs() != null) {
            notBlank(problems, "ibsCbs.finNFSe", r.ibsCbs().finNFSe());
            notBlank(problems, "ibsCbs.cIndOp", r.ibsCbs().cIndOp());
            notBlank(problems, "ibsCbs.indDest", r.ibsCbs().indDest());
            notBlank(problems, "ibsCbs.cst", r.ibsCbs().cst());
            notBlank(problems, "ibsCbs.cClassTrib", r.ibsCbs().cClassTrib());
            person(problems, "ibsCbs.dest", r.ibsCbs().dest());
        }
        return problems;
    }

    public static List<String> problems(CancelEventRequest r) {
        List<String> problems = new ArrayList<>();
        if (r == null) {
            return List.of("request body is empty");
        }
        notBlank(problems, "cMotivo", r.cMotivo());
        notBlank(problems, "xMotivo", r.xMotivo());
        if (isBlank(r.cnpjAutor()) && isBlank(r.cpfAutor())) {
            problems.add("the event author needs either cnpjAutor or cpfAutor");
        }
        return problems;
    }

    private static void person(List<String> problems, String name, EmitNfseRequest.Tomador p) {
        if (p != null) {
            notBlank(problems, name + ".nome", p.nome());
        }
    }

    private static void required(List<String> problems, String name, Object value) {
        if (value == null) {
            problems.add(name + " is required");
        }
    }

    private static void notBlank(List<String> problems, String name, String value) {
        if (isBlank(value)) {
            problems.add(name + " must not be blank");
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
