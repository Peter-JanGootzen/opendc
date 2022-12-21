import matplotlib.pyplot as plt
import matplotlib.patches as mpatches
import numpy as np
import ast
import matplotlib
matplotlib.rcParams['pdf.fonttype'] = 42
matplotlib.rcParams['ps.fonttype'] = 42
plt.rcParams['text.usetex'] = True

def getData(path):
    data = dict()
    with open(path, 'r') as f:
        for i, line in enumerate(f.readlines()):
            content = line.split(';')
            if i != 0 and len(content) == 4:
                reader = content[0].strip()
                metric = content[1].strip()
                value = content[2].strip()
                unit = content[3].replace('\n', '').strip()
                if value[0] == '[' and value[-1] == ']':
                    value = ast.literal_eval(value)
                data[metric] = [value, unit]
    return data


if __name__ == "__main__":
    default_path = "../../../../../../../results/"
    trace = "askalon_ee"
    scheduler = "taskflow"
    topology = "heterogeneous"
    data = getData(f'{default_path}/{trace}/{scheduler}/{topology}.csv')
    data_energy = [np.array(data['energyUsage'][0]).astype(float)]
    data_cpuUtil = [np.array(data['cpuUtilization'][0]).astype(float)]

    ###########################################################################
    # Staircase plot of active jobs over time.                                #
    ###########################################################################

    data_active_jobs = [np.array(data['guestsRunning'][0]).astype(float)]
    def staircase_average(a, n=3):
        steps = int(len(a)/n)
        steps_remainder = int(len(a)%n)
        staircase = np.zeros(len(a))
        for i in range(steps):
            staircase[i*n:(1+i)*n] = np.average(a[i*n:(1+i)*n])
        staircase[steps:steps_remainder] = np.average(a[steps:steps_remainder])
        return staircase

    fig = plt.figure(figsize=(12,4))
    ax = fig.subplots()
    ax.set_title("Guests running over time")
    ax.plot(data_active_jobs[0], color='green', alpha=0.1, zorder=10)
    staircase_avg = staircase_average(data_active_jobs[0], 10)
    ax.plot(staircase_avg, color='green', alpha=0.85, zorder=10)
    ax.set_xlabel("Scheduler Cycles")
    ax.set_ylabel("Active tasks")
    ax.set_ylim(bottom=0, top = (max(staircase_avg) + max(staircase_avg)*0.1))
    plt.grid(axis='x')
    plt.tight_layout()
    plt.savefig(f'[{trace}]-[{scheduler}]-[{topology}]-[{ax.get_title()}].pdf', transparent=True)
    plt.show()


    ###########################################################################
    # Line plot of energy usage compared to cpu utilization over time.        #
    ###########################################################################

    def moving_average(a, n=3):
        ret = np.cumsum(a, dtype=float)
        ret[n:] = ret[n:] - ret[:-n]
        return ret[n - 1:] / n
    fig = plt.figure(figsize=(12,4))
    ax = fig.subplots()
    ax.set_title("Workflow energy usage compared to CPU utilization over time")
    ax.plot(data_energy[0]/3600, color='red', alpha=0.1, zorder=10)
    ax.plot(moving_average(data_energy[0]/3600, 15), color='red', alpha=0.85, zorder=10)
    ax.set_xlabel("Scheduler Cycles")
    ax.set_ylabel("Energy usage (Wh)", color='red')
    ax.set_ylim(bottom=0)
    ax.hlines(np.max(data_energy[0]/3600), -10, len(data_cpuUtil[0])+10, linestyles='--', color='grey', alpha=0.7, zorder=0)
    ax.hlines(np.min(data_energy[0]/3600), -10, len(data_cpuUtil[0])+10, linestyles='--', color='grey', alpha=0.7, zorder=0)
    ax2=ax.twinx()
    ax2.plot(data_cpuUtil[0]*100, color='blue', alpha=0.1, zorder=5)
    ax2.plot(moving_average(data_cpuUtil[0]*100, 15), color='blue', alpha=0.85, zorder=5)
    ax2.set_ylabel("CPU Utilization (\%)", color='blue')
    ax2.set_ylim(0,101)
    plt.xlim(-10, len(data_cpuUtil[0])+10)
    ax.grid(False)
    ax2.grid(False)
    plt.tight_layout()
    plt.savefig(f'[{trace}]-[{scheduler}]-[{topology}]-[{ax.get_title()}].pdf', transparent=True)
    plt.show()


    ###########################################################################
    # Previous line plot and violin plots combined in a subplot.              #
    ###########################################################################

    # Line plot of energy usage compared to cpu utilization over time.
    fig = plt.figure(figsize=(15,5))
    ax = fig.subplots(2,3, gridspec_kw={'width_ratios': [10, 1, 1], 'height_ratios': [2, 1], 'hspace':0.0})

    def moving_average(a, n=3):
        ret = np.cumsum(a, dtype=float)
        ret[n:] = ret[n:] - ret[:-n]
        return ret[n - 1:] / n
    ax[0][0].set_title("Workflow energy usage compared to CPU utilization over time\nRolling average of 15")
    ax[0][0].plot(data_energy[0]/3600, color='red', alpha=0.1, zorder=10)
    ax[0][0].plot(moving_average(data_energy[0]/3600, 15), color='red', alpha=0.85, zorder=10)
    ax[0][0].set_ylabel("Energy usage (Wh)", color='red')
    ax[0][0].set_ylim(bottom=0)
    ax[0][0].hlines(np.max(data_energy[0]/3600), -10, len(data_cpuUtil[0])+10, linestyles='--', color='grey', alpha=0.7, zorder=0)
    ax[0][0].hlines(np.min(data_energy[0]/3600), -10, len(data_cpuUtil[0])+10, linestyles='--', color='grey', alpha=0.7, zorder=0)
    ax002=ax[0][0].twinx()
    ax002.plot(data_cpuUtil[0]*100, color='blue', alpha=0.1, zorder=5)
    ax002.plot(moving_average(data_cpuUtil[0]*100, 15), color='blue', alpha=0.85, zorder=5)
    ax002.set_ylabel("CPU Utilization (\%)", color='blue')
    ax002.set_ylim(0,100)
    ax[0][0].set_xlim(-10, len(data_cpuUtil[0])+10)
    ax[0][0].set_xticklabels([])
    ax[0][0].set_xticks([])
    ax[0][0].set_xticks([], minor=True)

    # Box plot combined with a violin plot of energy usage.
    ax[0][1].set_title("Energy usage\ndistribution")
    ax[0][1].set_ylabel("Energy usage (Wh)", color='red')
    ax[0][1].set_xlabel("Policy")
    ax[0][1].boxplot(data_energy[0]/3600, vert=True, showmeans=True, meanline=True, sym='', whis=1.5)
    violin_parts  = ax[0][1].violinplot(data_energy[0]/3600, vert=True, showmeans=True)
    for pc in violin_parts['bodies']:
        pc.set_facecolor('red')
        pc.set_edgecolor('black')
    #         pc.set_alpha(1)
    ax[0][1].set_ylim(bottom=0)
    ax[0][1].set_xticklabels([])

    # Box plot combined with a violin plot of cpu utilization.
    ax[0][2].set_title("CPU utilization\ndistribution")
    ax[0][2].set_ylabel("CPU Utilization (\%)", color='blue')
    ax[0][2].set_xlabel("Policy")
    ax[0][2].boxplot(data_cpuUtil[0]*100, vert=True, showmeans=True, meanline=True, sym='', whis=1.5)
    violin_parts  = ax[0][2].violinplot(data_cpuUtil[0]*100, vert=True, showmeans=True)
    for pc in violin_parts['bodies']:
        pc.set_facecolor('blue')
        pc.set_edgecolor('black')
    #         pc.set_alpha(1)
    ax[0][2].set_xticklabels([])
    ax[0][2].set_ylim(0,100)

    # Staircase plot showing the average active jobs over time
    data_active_jobs = [np.array(data['guestsRunning'][0]).astype(float)]
    def staircase_average(a, n=15):
        steps = int(len(a)/n)
        steps_remainder = int(len(a)%n)
        staircase = np.zeros(len(a))
        for i in range(steps):
            staircase[i*n:(1+i)*n] = np.average(a[i*n:(1+i)*n])
        staircase[steps:steps_remainder] = np.average(a[steps:steps_remainder])
        return staircase

    ax[1][0].plot(data_active_jobs[0], color='green', alpha=0.1, zorder=10)
    staircase_avg = staircase_average(data_active_jobs[0], 25)
    ax[1][0].plot(staircase_avg, color='green', alpha=1, zorder=10)
    ax[1][0].fill_between([i for i in range(len(staircase_avg))], staircase_avg, [0 for i in range(len(staircase_avg))], color='lightgreen', alpha=0.75, zorder=10, edgecolor='green')
    ax[1][0].set_xlabel("Scheduler Cycles")
    ax[1][0].set_ylabel("Active tasks", color='green')
    ax[1][0].set_ylim(bottom=0, top = (max(staircase_avg) + max(staircase_avg)*0.1))
    ax[1][0].set_xlabel("Scheduler Cycles")
    ax[1][0].set_xlim(-10, len(data_cpuUtil[0])+10)
    labels = [str(int(i)) for i in ax[1][0].get_yticks()]
    # labels[-1] = labels[-2] = ''
    ax[1][0].set_yticklabels(labels)

    ax[1][1].set_visible(False)
    ax[1][2].set_visible(False)

    plt.tight_layout()
    ax[0][0].grid(False)
    ax002.grid(False)
    ax[0][1].grid(True)
    ax[0][2].grid(True)
    ax[1][0].grid(False)
    plt.savefig(f'[{trace}]-[{scheduler}]-[{topology}]-[combined].pdf', transparent=True)
    plt.show()


    ###########################################################################
    # Previous subplot without the density violin plots.                      #
    ###########################################################################

    # Line plot of energy usage compared to cpu utilization over time.
    fig = plt.figure(figsize=(12.5,3.5))
    plt.rcParams.update({'font.size': 13})
    ax = fig.subplots(2,1, gridspec_kw={'height_ratios': [2, 1], 'hspace':0.0})

    def moving_average(a, n=3):
        ret = np.cumsum(a, dtype=float)
        ret[n:] = ret[n:] - ret[:-n]
        return ret[n - 1:] / n
    ax[0].plot(data_energy[0]/3600, color='red', alpha=0.1, zorder=10)
    ax[0].plot(moving_average(data_energy[0]/3600, 15), color='red', alpha=0.85, zorder=10)
    ax[0].set_ylabel("Energy usage (Wh)", color='red')
    ax[0].set_ylim(bottom=0)
    ax[0].hlines(np.max(data_energy[0]/3600), -10, len(data_cpuUtil[0])+10, linestyles='--', color='grey', alpha=0.7, zorder=0)
    ax[0].hlines(np.min(data_energy[0]/3600), -10, len(data_cpuUtil[0])+10, linestyles='--', color='grey', alpha=0.7, zorder=0)
    ax002=ax[0].twinx()
    ax002.plot(data_cpuUtil[0]*100, color='blue', alpha=0.1, zorder=5)
    ax002.plot(moving_average(data_cpuUtil[0]*100, 15), color='blue', alpha=0.85, zorder=5)
    ax002.set_ylabel("CPU Utilization (\%)", color='blue')
    ax002.set_ylim(0,100)
    ax[0].set_xlim(-10, len(data_cpuUtil[0])+10)
    ax[0].set_xticklabels([])
    ax[0].set_xticks([])
    ax[0].set_xticks([], minor=True)

    # Staircase plot showing the average active jobs over time
    data_active_jobs = [np.array(data['guestsRunning'][0]).astype(float)]
    def staircase_average(a, n=15):
        steps = int(len(a)/n)
        steps_remainder = int(len(a)%n)
        staircase = np.zeros(len(a))
        for i in range(steps):
            staircase[i*n:(1+i)*n] = np.average(a[i*n:(1+i)*n])
        staircase[steps:steps_remainder] = np.average(a[steps:steps_remainder])
        return staircase

    ax[1].plot(data_active_jobs[0], color='green', alpha=0.1, zorder=10)
    staircase_avg = staircase_average(data_active_jobs[0], 25)
    ax[1].plot(staircase_avg, color='green', alpha=1, zorder=10)
    ax[1].fill_between([i for i in range(len(staircase_avg))], staircase_avg, [0 for i in range(len(staircase_avg))], color='lightgreen', alpha=0.75, zorder=10, edgecolor='green')
    ax[1].set_xlabel("Scheduler Cycles")
    ax[1].set_ylabel("Active tasks", color='green')
    ax[1].set_ylim(bottom=0, top = (max(staircase_avg) + max(staircase_avg)*0.1))
    ax[1].set_xlabel("Scheduler Cycles")
    ax[1].set_xlim(-10, len(data_cpuUtil[0])+10)
    labels = [str(int(i)) for i in ax[1].get_yticks()]
    # labels[-1] = labels[-2] = ''
    ax[1].set_yticklabels(labels)

    plt.tight_layout()
    ax[0].grid(False)
    ax002.grid(False)
    ax[1].grid(False)
    plt.savefig(f'[{trace}]-[{scheduler}]-[{topology}]-[combined no density].pdf', transparent=True)
    plt.show()



    ###########################################################################
    # Experiment 1                                                            #
    ###########################################################################

    ###########################################################################
    # Violin plots of multiple schedulers (combined).                         #
    ###########################################################################

    default_path = "../../../../../../../results/"
    trace = "askalon_ee"
    schedulers = ["naive", "random", "taskflow"]
    topology = "heterogeneous"

    data_energies = []
    data_cpuUtils = []
    data_uptimes = []
    for scheduler in schedulers:
        data = getData(f'{default_path}/{trace}/{scheduler}/{topology}.csv')
        data_energy = [np.array(data['energyUsage'][0]).astype(float)]
        data_cpuUtil = [np.array(data['cpuUtilization'][0]).astype(float)]
        data_energies.append(data_energy[0]/3600)
        data_cpuUtils.append(data_cpuUtil[0]*100)
        data_uptimes.append(np.array(data['uptime'][0]).astype(float))

    fig = plt.figure(figsize=(15,4))
    plt.rcParams.update({'font.size': 15})
    ax = fig.subplots(1, 2)

    # Box plot combined with a violin plot of energy usage.
    ax[0].set_title("Energy usage per policy")
    ax[0].set_xlabel("Energy usage (Wh)")
    ax[0].boxplot(data_energies, vert=False, showmeans=True, meanline=True, sym='', whis=2, widths=0.3)
    violin_parts = ax[0].violinplot(data_energies, vert=False, showmeans=True, widths=0.8)
    for pc in violin_parts['bodies']:
        pc.set_facecolor('red')
        pc.set_edgecolor('black')
    ax[0].set_yticklabels(schedulers)

    # Box plot combined with a violin plot of cpu utilization.
    ax[1].set_title("CPU Utilization per policy")
    ax[1].set_xlabel("CPU Utilization (\%)")
    ax[1].boxplot(data_cpuUtils, vert=False, showmeans=True, meanline=True, sym='', whis=2, widths=0.3)
    violin_parts = ax[1].violinplot(data_cpuUtils, vert=False, showmeans=True, widths=0.8)
    for pc in violin_parts['bodies']:
        pc.set_facecolor('blue')
        pc.set_edgecolor('black')
    ax[1].set_yticklabels(schedulers)

    plt.subplots_adjust(left=0.1,
                        bottom=0.140,
                        right=0.9,
                        top=0.9,
                        wspace=0.4,
                        hspace=0.4)
    ax[0].grid(True)
    ax[1].grid(True)
    plt.savefig(f'[{trace}]-[multiple]-[{topology}]-[energy cpu dist].pdf', transparent=True)
    plt.show()

    ###########################################################################
    # Violin plots of multiple schedulers (separate).                         #
    ###########################################################################

    fig = plt.figure(figsize=(7.5,4))
    plt.rcParams.update({'font.size': 17})
    ax = fig.subplots()

    # Box plot combined with a violin plot of energy usage.
    ax.set_xlabel("Energy usage (Wh)")
    ax.boxplot(data_energies, vert=False, showmeans=True, meanline=True, sym='', whis=2, widths=0.3)
    violin_parts = ax.violinplot(data_energies, vert=False, showmeans=True, widths=0.8)
    for pc in violin_parts['bodies']:
        pc.set_facecolor('red')
        pc.set_edgecolor('black')
    ax.set_yticklabels(schedulers)
    plt.tight_layout()
    plt.grid(True)
    plt.savefig(f'[{trace}]-[multiple]-[{topology}]-[energy dist].pdf', transparent=True)
    plt.show()

    # Box plot combined with a violin plot of cpu utilization.
    fig = plt.figure(figsize=(7.5,4))
    plt.rcParams.update({'font.size': 17})
    ax = fig.subplots()
    ax.set_xlabel("CPU Utilization (\%)")
    ax.boxplot(data_cpuUtils, vert=False, showmeans=True, meanline=True, sym='', whis=2, widths=0.3)
    violin_parts = ax.violinplot(data_cpuUtils, vert=False, showmeans=True, widths=0.8)
    for pc in violin_parts['bodies']:
        pc.set_facecolor('blue')
        pc.set_edgecolor('black')
    ax.set_yticklabels(schedulers)
    plt.tight_layout()
    plt.grid(True)
    plt.savefig(f'[{trace}]-[multiple]-[{topology}]-[cpu dist].pdf', transparent=True)
    plt.show()


    energy_sums = [sum(i) for i in data_energies]
    print("Energy usage", *[i for i in zip(schedulers, energy_sums)], "(Wh)")
    print("Uptime", *[i for i in zip(schedulers, data_uptimes)], "(s)")

    ###########################################################################
    # Experiment 2                                                            #
    ###########################################################################

    ###########################################################################
    # Violin plots of multiple schedulers (separate).                         #
    ###########################################################################

    default_path = "../../../../../../../results/"
    trace = "askalon_ee"
    schedulers = "taskflow"
    topologies = ["heterogeneous", "homogeneous"]

    data_energies = []
    data_cpuUtils = []
    data_uptimes = []
    for topology in topologies:
        data = getData(f'{default_path}/{trace}/{scheduler}/{topology}.csv')
        data_energy = [np.array(data['energyUsage'][0]).astype(float)]
        data_cpuUtil = [np.array(data['cpuUtilization'][0]).astype(float)]
        data_energies.append(data_energy[0]/3600)
        data_cpuUtils.append(data_cpuUtil[0]*100)
        data_uptimes.append(np.array(data['uptime'][0]).astype(float))

    fig = plt.figure(figsize=(7.5,3))
    plt.rcParams.update({'font.size': 17})
    ax = fig.subplots()

    # Box plot combined with a violin plot of energy usage.
    ax.set_xlabel("Energy usage (Wh)")
    ax.boxplot(data_energies, vert=False, showmeans=True, meanline=True, sym='', whis=2, widths=0.3)
    violin_parts = ax.violinplot(data_energies, vert=False, showmeans=True, widths=0.8)
    for pc in violin_parts['bodies']:
        pc.set_facecolor('red')
        pc.set_edgecolor('black')
    ax.set_yticklabels(topologies)
    plt.tight_layout()
    plt.grid(True)
    plt.savefig(f'[{trace}]-[{scheduler}]-[multiple]-[energy dist].pdf', transparent=True)
    plt.show()

    # Box plot combined with a violin plot of cpu utilization.
    fig = plt.figure(figsize=(7.5,3))
    plt.rcParams.update({'font.size': 17})
    ax = fig.subplots()
    ax.set_xlabel("CPU Utilization (\%)")
    ax.boxplot(data_cpuUtils, vert=False, showmeans=True, meanline=True, sym='', whis=2, widths=0.3)
    violin_parts = ax.violinplot(data_cpuUtils, vert=False, showmeans=True, widths=0.8)
    for pc in violin_parts['bodies']:
        pc.set_facecolor('blue')
        pc.set_edgecolor('black')
    ax.set_yticklabels(topologies)
    plt.tight_layout()
    plt.grid(True)
    plt.savefig(f'[{trace}]-[{scheduler}]-[multiple]-[cpu dist].pdf', transparent=True)
    plt.show()

    ###########################################################################
    # Bar plots of total energy usage.                                        #
    ###########################################################################

    fig = plt.figure(figsize=(7.5,2.5))
    plt.rcParams.update({'font.size': 17})
    ax = fig.subplots()

    energy_sums = [sum(i) for i in data_energies]
    ax.barh(topologies, energy_sums, height = 0.4, edgecolor='black')

    plt.xlabel("Total energy usage (Wh)")
    plt.tight_layout()
    plt.grid(True)
    plt.show()

    print("Energy usage", *[i for i in zip(topologies, energy_sums)], "(Wh)")
    print("Uptime", *[i for i in zip(topologies, data_uptimes)], "(s)")



    ###########################################################################
    # Experiment 3                                                            #
    ###########################################################################

    ###########################################################################
    # Bar plots of total energy usage.                                        #
    ###########################################################################

    default_path = "../../../../../../../results/"
    traces = ["askalon_ee", "Pegasus_P1_parquet", "Pegasus_P7_parquet"]
    schedulers = "taskflow"
    topology = "heterogeneous"

    data_energies = []
    data_cpuUtils = []
    data_uptimes = []
    for trace in traces:
        data = getData(f'{default_path}/{trace}/{scheduler}/{topology}.csv')
        data_energy = [np.array(data['energyUsage'][0]).astype(float)]
        data_cpuUtil = [np.array(data['cpuUtilization'][0]).astype(float)]
        data_energies.append(data_energy[0]/3600)
        data_cpuUtils.append(data_cpuUtil[0]*100)
        data_uptimes.append(np.array(data['uptime'][0]).astype(float))

    fig = plt.figure(figsize=(7.5,2.5))
    plt.rcParams.update({'font.size': 17})
    ax = fig.subplots()

    energy_sums = [sum(i) for i in data_energies]
    ax.barh(traces, energy_sums, height = 0.4, edgecolor='black')

    plt.xlabel("Total energy usage (Wh)")
    plt.tight_layout()
    plt.grid(True)
    plt.show()

    print("Energy usage", *[i for i in zip(traces, energy_sums)], "(Wh)")
    print("Uptime", *[i for i in zip(traces, data_uptimes)], "(s)")



    ###########################################################################
    # Appendix                                                                #
    ###########################################################################

    ###########################################################################
    # Pegasus P1                                                              #
    ###########################################################################

    default_path = "../../../../../../../results/"
    trace = "Pegasus_P1_parquet"
    scheduler = "taskflow"
    topology = "heterogeneous"
    data = getData(f'{default_path}/{trace}/{scheduler}/{topology}.csv')
    data_energy = [np.array(data['energyUsage'][0]).astype(float)]
    data_cpuUtil = [np.array(data['cpuUtilization'][0]).astype(float)]

    # Line plot of energy usage compared to cpu utilization over time.
    fig = plt.figure(figsize=(12.5,3.5))
    plt.rcParams.update({'font.size': 13})
    ax = fig.subplots(2,1, gridspec_kw={'height_ratios': [2, 1], 'hspace':0.0})

    def moving_average(a, n=3):
        ret = np.cumsum(a, dtype=float)
        ret[n:] = ret[n:] - ret[:-n]
        return ret[n - 1:] / n
    ax[0].plot(data_energy[0]/3600, color='red', alpha=0.1, zorder=10)
    ax[0].plot(moving_average(data_energy[0]/3600, 15), color='red', alpha=0.85, zorder=10)
    ax[0].set_ylabel("Energy usage (Wh)", color='red')
    ax[0].set_ylim(bottom=0)
    ax[0].hlines(np.max(data_energy[0]/3600), -10, len(data_cpuUtil[0])+10, linestyles='--', color='grey', alpha=0.7, zorder=0)
    ax[0].hlines(np.min(data_energy[0]/3600), -10, len(data_cpuUtil[0])+10, linestyles='--', color='grey', alpha=0.7, zorder=0)
    ax002=ax[0].twinx()
    ax002.plot(data_cpuUtil[0]*100, color='blue', alpha=0.1, zorder=5)
    ax002.plot(moving_average(data_cpuUtil[0]*100, 15), color='blue', alpha=0.85, zorder=5)
    ax002.set_ylabel("CPU Utilization (\%)", color='blue')
    ax002.set_ylim(0,100)
    ax[0].set_xlim(-10, len(data_cpuUtil[0])+10)
    ax[0].set_xticklabels([])
    ax[0].set_xticks([])
    ax[0].set_xticks([], minor=True)

    # Staircase plot showing the average active jobs over time
    data_active_jobs = [np.array(data['guestsRunning'][0]).astype(float)]
    def staircase_average(a, n=15):
        steps = int(len(a)/n)
        steps_remainder = int(len(a)%n)
        staircase = np.zeros(len(a))
        for i in range(steps):
            staircase[i*n:(1+i)*n] = np.average(a[i*n:(1+i)*n])
        staircase[steps:steps_remainder] = np.average(a[steps:steps_remainder])
        return staircase

    ax[1].plot(data_active_jobs[0], color='green', alpha=0.1, zorder=10)
    staircase_avg = staircase_average(data_active_jobs[0], 15)
    ax[1].plot(staircase_avg, color='green', alpha=1, zorder=10)
    ax[1].fill_between([i for i in range(len(staircase_avg))], staircase_avg, [0 for i in range(len(staircase_avg))], color='lightgreen', alpha=0.75, zorder=10, edgecolor='green')
    ax[1].set_xlabel("Scheduler Cycles")
    ax[1].set_ylabel("Active tasks", color='green')
    ax[1].set_ylim(bottom=0, top = (max(staircase_avg) + max(staircase_avg)*0.1))
    ax[1].set_xlabel("Scheduler Cycles")
    ax[1].set_xlim(-10, len(data_cpuUtil[0])+10)
    labels = [str(int(i)) for i in ax[1].get_yticks()]
    # labels[-1] = labels[-2] = ''
    ax[1].set_yticklabels(labels)

    plt.tight_layout()
    ax[0].grid(False)
    ax002.grid(False)
    ax[1].grid(False)
    plt.savefig(f'[{trace}]-[{scheduler}]-[{topology}]-[combined no density].pdf', transparent=True)
    plt.show()


    ###########################################################################
    # Pegasus P7                                                              #
    ###########################################################################

    default_path = "../../../../../../../results/"
    trace = "Pegasus_P7_parquet"
    scheduler = "taskflow"
    topology = "heterogeneous"
    data = getData(f'{default_path}/{trace}/{scheduler}/{topology}.csv')
    data_energy = [np.array(data['energyUsage'][0]).astype(float)]
    data_cpuUtil = [np.array(data['cpuUtilization'][0]).astype(float)]

    # Line plot of energy usage compared to cpu utilization over time.
    fig = plt.figure(figsize=(12.5,3.5))
    plt.rcParams.update({'font.size': 13})
    ax = fig.subplots(2,1, gridspec_kw={'height_ratios': [2, 1], 'hspace':0.0})

    def moving_average(a, n=3):
        ret = np.cumsum(a, dtype=float)
        ret[n:] = ret[n:] - ret[:-n]
        return ret[n - 1:] / n
    ax[0].plot(data_energy[0]/3600, color='red', alpha=0.1, zorder=10)
    ax[0].plot(moving_average(data_energy[0]/3600, 15), color='red', alpha=0.85, zorder=10)
    ax[0].set_ylabel("Energy usage (Wh)", color='red')
    ax[0].set_ylim(bottom=0)
    ax[0].hlines(np.max(data_energy[0]/3600), -10, len(data_cpuUtil[0])+10, linestyles='--', color='grey', alpha=0.7, zorder=0)
    ax[0].hlines(np.min(data_energy[0]/3600), -10, len(data_cpuUtil[0])+10, linestyles='--', color='grey', alpha=0.7, zorder=0)
    ax002=ax[0].twinx()
    ax002.plot(data_cpuUtil[0]*100, color='blue', alpha=0.1, zorder=5)
    ax002.plot(moving_average(data_cpuUtil[0]*100, 15), color='blue', alpha=0.85, zorder=5)
    ax002.set_ylabel("CPU Utilization (\%)", color='blue')
    ax002.set_ylim(0,100)
    ax[0].set_xlim(-10, len(data_cpuUtil[0])+10)
    ax[0].set_xticklabels([])
    ax[0].set_xticks([])
    ax[0].set_xticks([], minor=True)

    # Staircase plot showing the average active jobs over time
    data_active_jobs = [np.array(data['guestsRunning'][0]).astype(float)]
    def staircase_average(a, n=15):
        steps = int(len(a)/n)
        steps_remainder = int(len(a)%n)
        staircase = np.zeros(len(a))
        for i in range(steps):
            staircase[i*n:(1+i)*n] = np.average(a[i*n:(1+i)*n])
        staircase[steps:steps_remainder] = np.average(a[steps:steps_remainder])
        return staircase

    ax[1].plot(data_active_jobs[0], color='green', alpha=0.1, zorder=10)
    staircase_avg = staircase_average(data_active_jobs[0], 25)
    ax[1].plot(staircase_avg, color='green', alpha=1, zorder=10)
    ax[1].fill_between([i for i in range(len(staircase_avg))], staircase_avg, [0 for i in range(len(staircase_avg))], color='lightgreen', alpha=0.75, zorder=10, edgecolor='green')
    ax[1].set_xlabel("Scheduler Cycles")
    ax[1].set_ylabel("Active tasks", color='green')
    ax[1].set_ylim(bottom=0, top = (max(staircase_avg) + max(staircase_avg)*0.1))
    ax[1].set_xlabel("Scheduler Cycles")
    ax[1].set_xlim(-10, len(data_cpuUtil[0])+10)
    labels = [str(int(i)) for i in ax[1].get_yticks()]
    # labels[-1] = labels[-2] = ''
    ax[1].set_yticklabels(labels)

    plt.tight_layout()
    ax[0].grid(False)
    ax002.grid(False)
    ax[1].grid(False)
    plt.savefig(f'[{trace}]-[{scheduler}]-[{topology}]-[combined no density].pdf', transparent=True)
    plt.show()
