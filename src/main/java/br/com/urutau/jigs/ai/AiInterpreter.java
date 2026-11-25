package br.com.urutau.jigs.ai;

import java.util.List;

interface AiInterpreter {
    boolean configured();

    Result interpret(Input input);

    record Input(List<Model> catalog, List<Turn> history, Result draft, String message) {}

    record Model(String model, List<WeightOptions> weights) {}

    record WeightOptions(int weight, List<String> colors) {}

    record Turn(String message, List<String> questions) {}

    record ProposedItem(String model, int weight, String color, int quantity) {}

    record Result(List<ProposedItem> items, List<String> questions, boolean needsClarification) {}
}
